# -*- coding: utf-8 -*-
"""astrbot_plugin_diet

拍照饮食管理插件。

设计目标：让饮食记录**不进入聊天上下文**。

  * 手机 APP 通过受 HMAC 保护的 Web API 上传餐食照片；
  * 照片落盘归档到插件数据目录，同时调用视觉模型分析营养；
  * 分析结果追加写入当日 jsonl 记录文件（纯文件存储，不产生聊天消息）；
  * 用户需要时用 /饮食 指令主动查看日报或阶段汇总。

Web API（全部挂在 /api/v1/plugins/extensions/astrbot_plugin_diet/ 之下，
使用 AstrBot 的 plugin scope API Key 调用）：

    GET  /health    健康检查
    POST /analyze   multipart 上传图片(file) -> 归档 + 视觉分析 + 写记录
    POST /archive   JSON(base64) 上传 -> 归档 + 可选分析（APP 离线补传用）
    GET  /records   查询某天记录 ?date=YYYY-MM-DD
    GET  /photo     取回归档照片 ?date=YYYY-MM-DD&name=xxx.jpg
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import json
import re
import time
import uuid
from pathlib import Path
from typing import Any

import aiohttp

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star
from astrbot.core.utils.astrbot_path import get_astrbot_plugin_data_path

try:  # 插件 Web API 需要 AstrBot 4.18+ 且启用了插件 Pages
    from astrbot.api.web import (
        error_response,
        file_response,
        json_response,
        request,
        stream_response,
    )

    WEB_API = True
except Exception:  # pragma: no cover - 兼容旧版本
    error_response = file_response = json_response = None  # type: ignore
    stream_response = None  # type: ignore
    request = None  # type: ignore
    WEB_API = False


PLUGIN_NAME = "astrbot_plugin_diet"
PLUGIN_VERSION = "1.1.0"
MAX_UPLOAD_BYTES = 12 * 1024 * 1024
MAX_TEXT_CHARS = 2000
ALLOWED_SUFFIX = {".jpg", ".jpeg", ".png", ".webp", ".heic"}

# 每日摄入目标（可在插件配置里改，也可由 APP 覆盖，覆盖值存在 state.json）
DEFAULT_TARGETS = {
    "calories_kcal": 2000.0,
    "protein_g": 75.0,
    "carbs_g": 250.0,
    "fat_g": 65.0,
}

# SSE 每条事件的前缀
SSE_PREFIX = "data: "

DEFAULT_PROMPT = """你是一位严谨、友善的专业营养师。请仔细观察这张餐食照片，估算每种食物的份量与营养。

输出格式**严格两行**：
第一行：一句简短的中文观察，40 字以内，说明你看到了什么，例如"盘中是炒饭、鸡蛋和虾仁，油量偏多"。
第二行：一个 JSON 对象（写成一行，不要换行，不要用 Markdown 代码块包裹）。

JSON 结构如下：
{"is_food":true,"title":"一句话概括这一餐","meal":"早餐或午餐或晚餐或加餐或零食","items":[{"name":"食物名","portion":"约150g","calories_kcal":230,"protein_g":12.5,"carbs_g":30.0,"fat_g":6.0}],"calories_kcal":520,"protein_g":30.0,"carbs_g":60.0,"fat_g":18.0,"confidence":0.8,"advice":"一句简短、具体、友善的建议"}

要求：
1. 若图片中没有任何食物，返回 {"is_food": false, "reason": "简短原因"}。
2. 所有数值都是估算值；calories_kcal / protein_g / carbs_g / fat_g 表示整餐合计。
3. confidence 是 0 到 1 之间的置信度。
4. advice 要具体（例如"蛋白质偏少，可以加一个鸡蛋或一杯无糖酸奶"），不要说空话。
5. 第二行必须是**能被 json.loads 直接解析**的完整 JSON，前后不要有任何多余字符。"""


TEXT_PROMPT = """你是一位严谨、友善的专业营养师。用户没有拍照，而是用文字描述了自己吃了什么。请根据这段描述估算份量与营养。

输出格式**严格两行**：
第一行：一句简短的中文观察，40 字以内，指出你如何理解这份描述，例如"按常见的兰州拉面份量估算，另加一个卤蛋"。
第二行：一个 JSON 对象（写成一行，不要换行，不要用 Markdown 代码块包裹）。

JSON 结构如下：
{"is_food":true,"title":"一句话概括这一餐","meal":"早餐或午餐或晚餐或加餐或零食","items":[{"name":"食物名","portion":"约150g","calories_kcal":230,"protein_g":12.5,"carbs_g":30.0,"fat_g":6.0}],"calories_kcal":520,"protein_g":30.0,"carbs_g":60.0,"fat_g":18.0,"confidence":0.6,"advice":"一句简短、具体、友善的建议"}

要求：
1. 描述里没有食物的，返回 {"is_food": false, "reason": "简短原因"}。
2. 用户对份量的描述可能很模糊（例如"一碗面"），按常见份量估算，并在 confidence 里体现不确定性。
3. 如果描述明显不完整（例如只写了"吃了饭"），照样给出估算，但在 advice 里点出缺了什么信息会算得更准。
4. 第二行必须是**能被 json.loads 直接解析**的完整 JSON，前后不要有任何多余字符。"""


def _pick(mapping: Any, key: str, default: Any = None) -> Any:
    """从请求的映射对象里安全取值。

    重要：AstrBot 的 PluginMultiDict（query / form / files 的返回类型）
    不是 dict 的子类，所以绝对不能用 isinstance(x, dict) 来判断，
    否则会静默取不到值——曾因此把上传的文件整个丢掉。
    """
    if mapping is None:
        return default
    getter = getattr(mapping, "get", None)
    if callable(getter):
        try:
            return getter(key, default)
        except TypeError:
            try:
                return getter(key)
            except Exception:  # noqa: BLE001
                return default
        except Exception:  # noqa: BLE001
            return default
    return default


def _f(value: Any, default: float = 0.0) -> float:
    """把模型返回的各种奇怪数值安全地转成 float。"""
    try:
        if value is None or value == "":
            return default
        return round(float(value), 1)
    except (TypeError, ValueError):
        return default


class DietPlugin(Star):
    def __init__(self, context: Context, config: Any = None):
        super().__init__(context)
        try:
            self.config = dict(config) if config else {}
        except Exception:
            self.config = {}
        self.data_dir = Path(get_astrbot_plugin_data_path()) / PLUGIN_NAME
        self.photo_dir = self.data_dir / "photos"
        self.record_dir = self.data_dir / "records"
        for d in (self.data_dir, self.photo_dir, self.record_dir):
            d.mkdir(parents=True, exist_ok=True)
        try:
            offset = int(self.config.get("timezone_offset_hours") or 8)
        except (TypeError, ValueError):
            offset = 8
        self.tz = dt.timezone(dt.timedelta(hours=offset))

        if WEB_API:
            self._register_web_apis()
        else:
            logger.warning("[diet] 当前 AstrBot 版本不支持插件 Web API，仅 /饮食 指令可用")

        logger.info("[diet] 数据目录: %s", self.data_dir)

    # ------------------------------------------------------------------ 注册

    def _register_web_apis(self) -> None:
        prefix = "/" + PLUGIN_NAME
        routes = [
            (prefix + "/health", self.api_health, ["GET"], "diet health check"),
            (prefix + "/analyze", self.api_analyze, ["POST"], "analyze a meal photo"),
            (prefix + "/analyze_text", self.api_analyze_text, ["POST"], "analyze a typed meal description"),
            (
                prefix + "/analyze_stream",
                self.api_analyze_stream,
                ["POST"],
                "analyze a meal photo, streamed as SSE",
            ),
            (
                prefix + "/analyze_text_stream",
                self.api_analyze_text_stream,
                ["POST"],
                "analyze a typed description, streamed as SSE",
            ),
            (prefix + "/summary", self.api_summary, ["GET"], "today's intake and daily targets"),
            (prefix + "/targets", self.api_targets, ["POST"], "update daily targets"),
            (prefix + "/archive", self.api_archive, ["POST"], "archive a photo (base64)"),
            (prefix + "/records", self.api_records, ["GET"], "list one day records"),
            (prefix + "/photo", self.api_photo, ["GET"], "fetch an archived photo"),
        ]
        for route, handler, methods, desc in routes:
            self.context.register_web_api(route, handler, methods, desc)
        logger.info("[diet] 已注册 %d 个 Web API", len(routes))

    def _secret(self) -> str:
        return str(self.config.get("hmac_secret") or "change-me-dietcam-secret")

    def _sign(self, exp: int) -> str:
        return hmac.new(
            self._secret().encode("utf-8"), str(exp).encode("utf-8"), hashlib.sha256
        ).hexdigest()

    def _authorized(self) -> bool:
        """校验 APP 带来的 X-Diet-Token: "<exp>.<hmac_sha256(secret, exp)>"。"""
        try:
            token = str(request.headers.get("X-Diet-Token") or "").strip()
        except Exception:
            return False
        if "." not in token:
            return False
        raw_exp, sig = token.split(".", 1)
        try:
            exp = int(raw_exp)
        except (TypeError, ValueError):
            return False
        if exp < int(time.time()):
            return False
        return hmac.compare_digest(sig.strip().lower(), self._sign(exp))

    # ------------------------------------------------------------- 存储与工具

    def _today(self) -> str:
        return dt.datetime.now(self.tz).strftime("%Y-%m-%d")

    def _record_file(self, day: str) -> Path:
        return self.record_dir / (day + ".jsonl")

    def _append_record(self, day: str, record: dict) -> None:
        path = self._record_file(day)
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")

    def _load_records(self, day: str) -> list[dict]:
        path = self._record_file(day)
        if not path.exists():
            return []
        out: list[dict] = []
        try:
            with path.open("r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        item = json.loads(line)
                        if isinstance(item, dict):
                            out.append(item)
                    except json.JSONDecodeError:
                        continue
        except OSError as exc:
            logger.error("[diet] 读取记录失败 %s: %s", path, exc)
        return out

    def _totals(self, records: list[dict]) -> dict:
        acc = {"calories_kcal": 0.0, "protein_g": 0.0, "carbs_g": 0.0, "fat_g": 0.0}
        for rec in records:
            if not rec.get("is_food", True):
                continue
            for key in acc:
                acc[key] += _f(rec.get(key))
        return {k: round(v, 1) for k, v in acc.items()}

    def _safe_photo(self, day: str, name: str) -> Path | None:
        if not day or not name:
            return None
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
            return None
        clean = Path(name).name
        if clean != name or not clean:
            return None
        return self.photo_dir / day / clean

    @staticmethod
    def _guess_meal(moment: dt.datetime) -> str:
        hour = moment.hour
        if hour < 5:
            return "夜宵"
        if hour < 10:
            return "早餐"
        if hour < 14:
            return "午餐"
        if hour < 17:
            return "加餐"
        if hour < 21:
            return "晚餐"
        return "夜宵"

    def _json_conf(self, key: str) -> dict:
        raw = self.config.get(key)
        if isinstance(raw, dict):
            return dict(raw)
        if not raw:
            return {}
        try:
            parsed = json.loads(str(raw))
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            logger.warning("[diet] 配置 %s 不是合法 JSON，已忽略", key)
            return {}

    # ---------------------------------------------------------- 状态与目标

    def _state_file(self) -> Path:
        return self.data_dir / "state.json"

    def _load_state(self) -> dict:
        path = self._state_file()
        if not path.exists():
            return {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, json.JSONDecodeError):
            return {}

    def _save_state(self, state: dict) -> None:
        try:
            self._state_file().write_text(
                json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
            )
        except OSError as exc:
            logger.warning("[diet] 写入 state.json 失败: %s", exc)

    def _targets(self) -> dict:
        """每日目标：插件配置为底，APP 写入 state.json 的覆盖值优先。"""
        targets = dict(DEFAULT_TARGETS)
        for key in DEFAULT_TARGETS:
            raw = self.config.get("target_" + key)
            if raw in (None, ""):
                continue
            try:
                targets[key] = float(raw)
            except (TypeError, ValueError):
                continue
        override = self._load_state().get("targets")
        if isinstance(override, dict):
            for key in DEFAULT_TARGETS:
                try:
                    if override.get(key) not in (None, ""):
                        targets[key] = float(override[key])
                except (TypeError, ValueError):
                    continue
        return {k: round(v, 1) for k, v in targets.items()}

    @staticmethod
    def _sse(payload: dict) -> str:
        return SSE_PREFIX + json.dumps(payload, ensure_ascii=False) + "\n\n"

    def _save_photo(self, upload: Any) -> tuple[Path, str, str]:
        """把上传对象保存到 photos/<day>/ 下，返回 (路径, 文件名, 日期)。"""
        now = dt.datetime.now(self.tz)
        day = now.strftime("%Y-%m-%d")
        day_dir = self.photo_dir / day
        day_dir.mkdir(parents=True, exist_ok=True)
        original = str(getattr(upload, "filename", "") or "photo.jpg")
        suffix = Path(original).suffix.lower()
        if suffix not in ALLOWED_SUFFIX:
            suffix = ".jpg"
        name = now.strftime("%H%M%S") + "_" + uuid.uuid4().hex[:8] + suffix
        target = day_dir / name
        return target, name, day

    # ------------------------------------------------------------------ 分析

    async def _analyze(self, path: Path | None = None, note: str = "") -> dict:
        """分析一餐。

        path 为 None 时表示纯文字模式（用户直接打字描述吃了什么）；
        否则以照片为主，note 作为补充说明一起交给模型。
        """
        mode = str(self.config.get("llm_mode") or "openai_compatible")
        if path is None:
            prompt = str(self.config.get("analyze_prompt_text") or "").strip() or TEXT_PROMPT
        else:
            prompt = str(self.config.get("analyze_prompt") or "").strip() or DEFAULT_PROMPT
        note = (note or "").strip()
        if note:
            prompt = prompt + "\n\n用户的补充说明：" + note
        try:
            if mode == "astrbot_provider":
                text, engine = await self._analyze_via_astrbot(path, prompt)
            else:
                text, engine = await self._analyze_openai(path, prompt)
        except Exception as exc:  # noqa: BLE001 - 需要把错误回传给 APP
            logger.error("[diet] 视觉模型调用失败: %s", exc, exc_info=True)
            return {
                "is_food": True,
                "title": "分析失败",
                "items": [],
                "advice": "模型调用失败：" + str(exc)[:200],
                "error": str(exc)[:500],
                "_engine": mode,
            }
        parsed = self._parse_json(text)
        parsed["_engine"] = engine
        return parsed

    def _openai_request(self, path: Path | None, prompt: str, stream: bool = False):
        """拼装 OpenAI 兼容请求，普通与流式两路共用。"""
        base = str(self.config.get("base_url") or "").strip().rstrip("/")
        if not base:
            raise RuntimeError("base_url 未配置")
        if not base.endswith("/chat/completions"):
            base = base + "/chat/completions"
        model = str(self.config.get("model") or "").strip()
        key = str(self.config.get("api_key") or "").strip()

        headers = {"Content-Type": "application/json"}
        if key:
            headers["Authorization"] = "Bearer " + key
        headers.update(self._json_conf("extra_headers"))

        content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        if path is not None:
            mime = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
            b64 = base64.b64encode(path.read_bytes()).decode("ascii")
            content.append(
                {"type": "image_url", "image_url": {"url": "data:" + mime + ";base64," + b64}}
            )
        payload: dict[str, Any] = {
            "model": model,
            "messages": [{"role": "user", "content": content}],
            "temperature": 0.2,
            "max_tokens": 1024,
        }
        if stream:
            payload["stream"] = True
        payload.update(self._json_conf("extra_body"))

        try:
            timeout_s = float(self.config.get("request_timeout") or 120)
        except (TypeError, ValueError):
            timeout_s = 120.0
        return base, payload, headers, timeout_s, model

    async def _analyze_openai(self, path: Path | None, prompt: str) -> tuple[str, str]:
        base, payload, headers, timeout_s, model = self._openai_request(path, prompt)
        timeout = aiohttp.ClientTimeout(total=timeout_s)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.post(base, json=payload, headers=headers) as resp:
                body = await resp.text()
                if resp.status >= 400:
                    raise RuntimeError("HTTP %s: %s" % (resp.status, body[:300]))
        try:
            data = json.loads(body)
            text = data["choices"][0]["message"]["content"]
        except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
            raise RuntimeError("响应格式不符合 OpenAI 规范: " + body[:300]) from exc
        if isinstance(text, list):  # 某些服务返回分段 content
            text = "".join(
                part.get("text", "") for part in text if isinstance(part, dict)
            )
        return str(text or ""), "openai:" + (model or "unknown")

    async def _analyze_via_astrbot(self, path: Path | None, prompt: str) -> tuple[str, str]:
        provider_id = str(self.config.get("astrbot_provider_id") or "").strip()
        if not provider_id:
            provider = None
            getter = getattr(self.context, "get_using_provider_async", None)
            try:
                provider = await getter() if callable(getter) else self.context.get_using_provider()
            except Exception as exc:  # noqa: BLE001
                raise RuntimeError("无法获取默认模型提供商: %s" % exc) from exc
            if provider is None:
                raise RuntimeError("AstrBot 中没有可用的模型提供商")
            cfg = getattr(provider, "provider_config", None)
            if isinstance(cfg, dict):
                provider_id = str(cfg.get("id") or "")
            if not provider_id:
                provider_id = str(getattr(provider, "id", "") or "")
        if not provider_id:
            raise RuntimeError("无法确定提供商 ID，请在插件配置里填写 astrbot_provider_id")

        image_urls: list[str] | None = None
        if path is not None:
            mime = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
            b64 = base64.b64encode(path.read_bytes()).decode("ascii")
            image_urls = ["data:" + mime + ";base64," + b64]
        resp = await self.context.llm_generate(
            chat_provider_id=provider_id,
            prompt=prompt,
            image_urls=image_urls,
        )
        text = getattr(resp, "completion_text", "") or ""
        return str(text), "astrbot:" + provider_id

    async def _stream_model(self, path: Path | None, note: str = ""):
        """异步生成器，逐段产出模型输出。

        只在 openai_compatible 模式下做真正的增量读取；
        astrbot_provider 模式拿不到流式接口，退化为一次性产出（内容不变，只是不分段）。
        """
        mode = str(self.config.get("llm_mode") or "openai_compatible")
        if path is None:
            prompt = str(self.config.get("analyze_prompt_text") or "").strip() or TEXT_PROMPT
        else:
            prompt = str(self.config.get("analyze_prompt") or "").strip() or DEFAULT_PROMPT
        note = (note or "").strip()
        if note:
            prompt = prompt + "\n\n用户的补充说明：" + note

        if mode != "openai_compatible":
            text, engine = await self._analyze(path, note)
            yield ("meta", engine)
            yield ("delta", json.dumps(text, ensure_ascii=False) if isinstance(text, dict) else str(text))
            return

        base, payload, headers, timeout_s, model = self._openai_request(path, prompt, stream=True)
        # 流式响应间隔可能较长，sock_read 单独放宽
        timeout = aiohttp.ClientTimeout(total=None, sock_connect=30.0, sock_read=timeout_s)
        yielded_any = False
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.post(base, json=payload, headers=headers) as resp:
                if resp.status >= 400:
                    body = await resp.text()
                    raise RuntimeError("HTTP %s: %s" % (resp.status, body[:300]))
                async for raw in resp.content:
                    line = raw.decode("utf-8", "replace").strip()
                    if not line or not line.startswith("data:"):
                        continue
                    chunk = line[5:].strip()
                    if chunk == "[DONE]":
                        break
                    try:
                        obj = json.loads(chunk)
                    except json.JSONDecodeError:
                        continue
                    choices = obj.get("choices") or []
                    if not choices:
                        continue
                    delta = choices[0].get("delta") or {}
                    piece = delta.get("content")
                    if isinstance(piece, list):
                        piece = "".join(
                            p.get("text", "") for p in piece if isinstance(p, dict)
                        )
                    if not piece:
                        continue
                    if not yielded_any:
                        yield ("meta", "openai:" + (model or "unknown"))
                        yielded_any = True
                    yield ("delta", str(piece))
        if not yielded_any:
            yield ("meta", "openai:" + (model or "unknown"))

    @staticmethod
    def _parse_json(text: str) -> dict:
        """从模型输出里稳健地抠出 JSON。"""
        if not text or not text.strip():
            return {"is_food": True, "title": "解析失败", "items": [], "advice": "模型没有返回内容"}
        cleaned = text.strip()
        fence = chr(96) * 3
        if cleaned.startswith(fence):
            cleaned = cleaned[len(fence):]
        if cleaned.endswith(fence):
            cleaned = cleaned[: -len(fence)]
        cleaned = cleaned.strip()
        if cleaned.lower().startswith("json"):
            cleaned = cleaned[4:].strip()

        match = re.search(r"\{.*\}", cleaned, re.S)
        candidate = match.group(0) if match else cleaned
        try:
            data = json.loads(candidate)
        except json.JSONDecodeError:
            return {
                "is_food": True,
                "title": "解析失败",
                "items": [],
                "advice": cleaned[:200],
            }
        if isinstance(data, dict):
            return data
        return {"is_food": True, "title": "解析失败", "items": [], "advice": str(data)[:200]}

    def _build_record(
        self,
        moment: dt.datetime,
        day: str,
        photo_name: str,
        result: dict,
        source: str = "app",
        note: str = "",
    ) -> dict:
        is_food = bool(result.get("is_food", True))
        items = result.get("items")
        if not isinstance(items, list):
            items = []
        return {
            "id": uuid.uuid4().hex[:12],
            "date": day,
            "time": moment.strftime("%H:%M"),
            "ts": int(moment.timestamp()),
            "photo": photo_name,
            "source": source,
            "note": str(note or "")[:200],
            "is_food": is_food,
            "title": str(result.get("title") or result.get("reason") or ("未识别到食物" if not is_food else "一餐"))[:80],
            "meal": str(result.get("meal") or self._guess_meal(moment))[:16],
            "calories_kcal": _f(result.get("calories_kcal")),
            "protein_g": _f(result.get("protein_g")),
            "carbs_g": _f(result.get("carbs_g")),
            "fat_g": _f(result.get("fat_g")),
            "confidence": _f(result.get("confidence")),
            "items": items,
            "advice": str(result.get("advice") or "")[:300],
            "engine": str(result.get("_engine") or ""),
        }

    # -------------------------------------------------------------- Web API

    async def api_health(self):
        return json_response(
            {
                "ok": True,
                "plugin": PLUGIN_NAME,
                "version": PLUGIN_VERSION,
                "server_time": int(time.time()),
                "data_dir": str(self.data_dir),
                # 供 APP 探测能力，缺失的功能可自动降级
                "features": {
                    "stream": bool(WEB_API and stream_response is not None),
                    "summary": True,
                    "targets": True,
                    "text": True,
                    "archive": True,
                },
                "targets": self._targets(),
            }
        )

    async def api_analyze(self):
        if not self._authorized():
            return error_response("unauthorized")
        try:
            files = await request.files()
        except Exception as exc:  # noqa: BLE001
            return error_response("解析上传内容失败: " + str(exc)[:200])

        upload = _pick(files, "file")
        if upload is None and files:
            # 客户端用了别的字段名也认，尽量别让它白跑一趟
            try:
                upload = next(iter(files.values()))
            except (StopIteration, TypeError):
                upload = None
        if upload is None:
            return error_response("缺少文件字段 file")

        note = ""
        try:
            form = await request.form()
            note = str(_pick(form, "note", "") or "")
        except Exception:  # noqa: BLE001
            note = ""

        target, name, day = self._save_photo(upload)
        try:
            await upload.save(target)
        except Exception as exc:  # noqa: BLE001
            logger.error("[diet] 保存照片失败: %s", exc, exc_info=True)
            return error_response("保存照片失败: " + str(exc)[:200])

        size = target.stat().st_size if target.exists() else 0
        if size == 0:
            target.unlink(missing_ok=True)
            return error_response("上传内容为空")
        if size > MAX_UPLOAD_BYTES:
            target.unlink(missing_ok=True)
            return error_response("图片过大")

        moment = dt.datetime.now(self.tz)
        result = await self._analyze(target, note=note)
        record = self._build_record(moment, day, name, result, source="app", note=note)
        self._append_record(day, record)
        logger.info(
            "[diet] 已归档 %s (%d bytes)，%s kcal",
            name,
            size,
            record["calories_kcal"],
        )
        return json_response({"ok": True, "record": record})

    async def api_analyze_text(self):
        """纯文字记录：用户直接打字描述吃了什么。"""
        if not self._authorized():
            return error_response("unauthorized")
        payload = await request.json(default={})
        if not hasattr(payload, "get"):
            payload = {}
        text = str(_pick(payload, "text", "") or "").strip()
        if not text:
            return error_response("缺少 text")
        if len(text) > MAX_TEXT_CHARS:
            return error_response("文字过长（上限 %d 字）" % MAX_TEXT_CHARS)

        moment = dt.datetime.now(self.tz)
        day = moment.strftime("%Y-%m-%d")
        result = await self._analyze(None, note=text)
        record = self._build_record(
            moment, day, "", result, source="app-text", note=text
        )
        self._append_record(day, record)
        logger.info("[diet] 已记录文字饮食：%s（%s kcal）", text[:30], record["calories_kcal"])
        return json_response({"ok": True, "record": record})

    async def _stream_analyze(self, path, note, day, photo_name, source, moment):
        """流式分析的 SSE 事件序列：start -> meta* -> delta* -> done|error。"""
        yield self._sse({"type": "start", "time": moment.strftime("%H:%M")})
        buf: list[str] = []
        engine = ""
        try:
            async for kind, value in self._stream_model(path, note):
                if kind == "meta":
                    engine = str(value)
                    yield self._sse({"type": "meta", "engine": engine})
                else:
                    buf.append(str(value))
                    yield self._sse({"type": "delta", "text": value})
        except Exception as exc:  # noqa: BLE001 - 错误要回传给客户端
            logger.error("[diet] 流式分析失败: %s", exc, exc_info=True)
            yield self._sse({"type": "error", "message": str(exc)[:300]})
            return

        parsed = self._parse_json("".join(buf))
        parsed["_engine"] = engine or "stream"
        record = self._build_record(
            moment, day, photo_name, parsed, source=source, note=note
        )
        self._append_record(day, record)
        logger.info("[diet] 流式归档 %s，%s kcal", photo_name or "(文字)", record["calories_kcal"])
        yield self._sse({"type": "done", "record": record})

    async def api_analyze_stream(self):
        """与 /analyze 相同，但以 SSE 逐步返回模型输出。"""
        if not self._authorized():
            return error_response("unauthorized")
        if not WEB_API or stream_response is None:
            return error_response("当前 AstrBot 版本不支持流式响应")
        try:
            files = await request.files()
        except Exception as exc:  # noqa: BLE001
            return error_response("解析上传内容失败: " + str(exc)[:200])

        upload = _pick(files, "file")
        if upload is None and files:
            try:
                upload = next(iter(files.values()))
            except (StopIteration, TypeError):
                upload = None
        if upload is None:
            return error_response("缺少文件字段 file")

        note = ""
        try:
            form = await request.form()
            note = str(_pick(form, "note", "") or "")
        except Exception:  # noqa: BLE001
            note = ""

        target, name, day = self._save_photo(upload)
        try:
            await upload.save(target)
        except Exception as exc:  # noqa: BLE001
            return error_response("保存照片失败: " + str(exc)[:200])

        size = target.stat().st_size if target.exists() else 0
        if size == 0:
            target.unlink(missing_ok=True)
            return error_response("上传内容为空")
        if size > MAX_UPLOAD_BYTES:
            target.unlink(missing_ok=True)
            return error_response("图片过大")

        moment = dt.datetime.now(self.tz)
        return stream_response(
            self._stream_analyze(target, note, day, name, "app", moment)
        )

    async def api_analyze_text_stream(self):
        """与 /analyze_text 相同，但以 SSE 逐步返回模型输出。"""
        if not self._authorized():
            return error_response("unauthorized")
        if not WEB_API or stream_response is None:
            return error_response("当前 AstrBot 版本不支持流式响应")
        payload = await request.json(default={})
        if not hasattr(payload, "get"):
            payload = {}
        text = str(_pick(payload, "text", "") or "").strip()
        if not text:
            return error_response("缺少 text")
        if len(text) > MAX_TEXT_CHARS:
            return error_response("文字过长（上限 %d 字）" % MAX_TEXT_CHARS)

        moment = dt.datetime.now(self.tz)
        day = moment.strftime("%Y-%m-%d")
        return stream_response(
            self._stream_analyze(None, text, day, "", "app-text", moment)
        )

    async def api_summary(self):
        """主页用：当天摄入合计、每日目标与记录明细。"""
        if not self._authorized():
            return error_response("unauthorized")
        day = str(_pick(request.query, "date", "") or self._today())
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
            return error_response("date 格式应为 YYYY-MM-DD")
        records = self._load_records(day)
        return json_response(
            {
                "ok": True,
                "date": day,
                "count": len(records),
                "totals": self._totals(records),
                "targets": self._targets(),
                "records": records,
            }
        )

    async def api_targets(self):
        """由 APP 调整每日目标，存入 state.json（不动 AstrBot 的插件配置）。"""
        if not self._authorized():
            return error_response("unauthorized")
        payload = await request.json(default={})
        if not hasattr(payload, "get"):
            payload = {}
        state = self._load_state()
        current = dict(state.get("targets") or {})
        updated: dict[str, float] = {}
        for key in DEFAULT_TARGETS:
            raw = _pick(payload, key, None)
            if raw is None or raw == "":
                continue
            try:
                value = float(raw)
            except (TypeError, ValueError):
                return error_response("%s 必须是数字" % key)
            if not (0 <= value <= 100000):
                return error_response("%s 超出合理范围" % key)
            current[key] = value
            updated[key] = value
        if not updated:
            return error_response("没有可更新的目标字段")
        state["targets"] = current
        self._save_state(state)
        return json_response({"ok": True, "updated": updated, "targets": self._targets()})

    async def api_archive(self):
        if not self._authorized():
            return error_response("unauthorized")
        payload = await request.json(default={})
        if not hasattr(payload, "get"):
            payload = {}
        raw_b64 = str(_pick(payload, "content_base64", "") or "")
        if not raw_b64:
            return error_response("缺少 content_base64")
        try:
            blob = base64.b64decode(raw_b64, validate=False)
        except (ValueError, TypeError) as exc:
            return error_response("base64 解码失败: " + str(exc)[:120])
        if not blob:
            return error_response("内容为空")
        if len(blob) > MAX_UPLOAD_BYTES:
            return error_response("图片过大")

        moment = dt.datetime.now(self.tz)
        day = moment.strftime("%Y-%m-%d")
        day_dir = self.photo_dir / day
        day_dir.mkdir(parents=True, exist_ok=True)
        suffix = Path(str(_pick(payload, "filename", "photo.jpg") or "photo.jpg")).suffix.lower()
        if suffix not in ALLOWED_SUFFIX:
            suffix = ".jpg"
        name = moment.strftime("%H%M%S") + "_" + uuid.uuid4().hex[:8] + suffix
        target = day_dir / name
        target.write_bytes(blob)

        if _pick(payload, "analyze", True) is False:
            return json_response({"ok": True, "saved": name, "date": day})

        note = str(_pick(payload, "note", "") or "")
        result = await self._analyze(target, note=note)
        record = self._build_record(
            moment, day, name, result, source=str(_pick(payload, "source", "") or "app-retry"),
            note=note,
        )
        self._append_record(day, record)
        return json_response({"ok": True, "record": record})

    async def api_records(self):
        if not self._authorized():
            return error_response("unauthorized")
        day = str(_pick(request.query, "date", "") or self._today())
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
            return error_response("date 格式应为 YYYY-MM-DD")
        records = self._load_records(day)
        return json_response(
            {
                "ok": True,
                "date": day,
                "count": len(records),
                "totals": self._totals(records),
                "records": records,
            }
        )

    async def api_photo(self):
        if not self._authorized():
            return error_response("unauthorized")
        day = str(_pick(request.query, "date", "") or "")
        name = str(_pick(request.query, "name", "") or "")
        path = self._safe_photo(day, name)
        if path is None or not path.is_file():
            return error_response("not found")
        return file_response(path)

    # --------------------------------------------------------------- 指令

    def _render_day(self, day: str) -> str:
        records = self._load_records(day)
        if not records:
            return "📭 " + day + " 还没有饮食记录。\n拍一张餐食照片上传后，记录会自动出现在这里。"
        totals = self._totals(records)
        lines = ["🍽️ 饮食日报 · " + day, ""]
        for rec in records:
            if not rec.get("is_food", True):
                mark = "❔"
            elif rec.get("source") == "app-text":
                mark = "✍️"   # 纯文字记录，没有照片
            else:
                mark = "📷"
            lines.append(
                "%s %s %s｜%s · %g kcal"
                % (
                    mark,
                    rec.get("time", ""),
                    rec.get("meal", ""),
                    rec.get("title", ""),
                    _f(rec.get("calories_kcal")),
                )
            )
            for item in (rec.get("items") or [])[:6]:
                if isinstance(item, dict):
                    lines.append(
                        "    · %s %s %g kcal"
                        % (
                            item.get("name", "?"),
                            item.get("portion", ""),
                            _f(item.get("calories_kcal")),
                        )
                    )
            if rec.get("advice"):
                lines.append("    💡 " + str(rec["advice"]))
        lines.append("")
        lines.append(
            "合计：%g kcal｜蛋白 %g g｜碳水 %g g｜脂肪 %g g"
            % (
                totals["calories_kcal"],
                totals["protein_g"],
                totals["carbs_g"],
                totals["fat_g"],
            )
        )
        lines.append("（共 %d 条记录）" % len(records))
        return "\n".join(lines)

    def _render_range(self, days: list[str], title: str) -> str:
        all_records: list[dict] = []
        per_day: list[tuple[str, dict, int]] = []
        for day in days:
            records = self._load_records(day)
            if records:
                all_records.extend(records)
                per_day.append((day, self._totals(records), len(records)))
        if not all_records:
            return "📭 " + title + " 还没有任何饮食记录。"
        totals = self._totals(all_records)
        lines = ["📊 " + title + " 汇总", ""]
        for day, day_totals, count in per_day:
            lines.append(
                "· %s  %g kcal（蛋白 %g / 碳水 %g / 脂肪 %g，%d 餐）"
                % (
                    day,
                    day_totals["calories_kcal"],
                    day_totals["protein_g"],
                    day_totals["carbs_g"],
                    day_totals["fat_g"],
                    count,
                )
            )
        days_count = max(len(per_day), 1)
        lines.append("")
        lines.append("合计：%g kcal" % totals["calories_kcal"])
        lines.append(
            "日均：%g kcal｜蛋白 %g g｜碳水 %g g｜脂肪 %g g"
            % (
                totals["calories_kcal"] / days_count,
                totals["protein_g"] / days_count,
                totals["carbs_g"] / days_count,
                totals["fat_g"] / days_count,
            )
        )
        return "\n".join(lines)

    def _remember_umo(self, event: AstrMessageEvent) -> None:
        """顺手记下会话标识，方便以后做定时推送，不会主动发消息。"""
        if not self.config.get("remember_umo", True):
            return
        umo = getattr(event, "unified_msg_origin", "") or ""
        if not umo:
            return
        state = self._load_state()
        if state.get("last_umo") != umo:
            state["last_umo"] = umo
            state["updated_at"] = int(time.time())
            self._save_state(state)

    @filter.command("饮食")
    async def cmd_diet(self, event: AstrMessageEvent, *_ignored):
        """查看饮食记录：/饮食、/饮食 昨天、/饮食 2026-06-27、/饮食 本周、/饮食 本月"""
        self._remember_umo(event)
        raw = (event.message_str or "").strip()
        arg = re.sub(r"^[/／]?\s*饮食\s*", "", raw).strip()
        arg = arg.replace(" ", "")
        now = dt.datetime.now(self.tz)
        today = now.strftime("%Y-%m-%d")

        if arg in ("", "今天", "今日", "当天"):
            yield event.plain_result(self._render_day(today))
            return
        if arg in ("昨天", "昨日"):
            day = (now - dt.timedelta(days=1)).strftime("%Y-%m-%d")
            yield event.plain_result(self._render_day(day))
            return
        if arg in ("本周", "最近7天", "近7天", "周"):
            days = [(now - dt.timedelta(days=i)).strftime("%Y-%m-%d") for i in range(6, -1, -1)]
            yield event.plain_result(self._render_range(days, "最近 7 天"))
            return
        if arg in ("本月", "这个月", "月"):
            days = [
                (now.replace(day=1) + dt.timedelta(days=i)).strftime("%Y-%m-%d")
                for i in range(now.day)
            ]
            yield event.plain_result(self._render_range(days, now.strftime("%Y 年 %m 月")))
            return
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", arg):
            yield event.plain_result(self._render_day(arg))
            return
        if re.fullmatch(r"\d{1,2}-\d{1,2}", arg):
            guess = "%d-%s" % (now.year, arg.zfill(5))
            yield event.plain_result(self._render_day(guess))
            return

        yield event.plain_result(
            "用法：\n"
            "/饮食            今天\n"
            "/饮食 昨天\n"
            "/饮食 2026-06-27  指定日期\n"
            "/饮食 本周        最近 7 天汇总\n"
            "/饮食 本月        当月汇总"
        )

    @filter.command("饮食状态")
    async def cmd_diet_status(self, event: AstrMessageEvent, *_ignored):
        """检查饮食插件的运行状态与最近记录数量"""
        mode = str(self.config.get("llm_mode") or "openai_compatible")
        model = str(self.config.get("model") or "(未设置)")
        today = self._today()
        count = len(self._load_records(today))
        photo_count = len(list((self.photo_dir / today).glob("*"))) if (self.photo_dir / today).is_dir() else 0
        yield event.plain_result(
            "🍱 饮食插件状态\n"
            "版本：%s\n"
            "Web API：%s\n"
            "模型模式：%s\n"
            "模型：%s\n"
            "数据目录：%s\n"
            "今日记录：%d 条，照片：%d 张"
            % (
                PLUGIN_VERSION,
                "已启用" if WEB_API else "不可用（AstrBot 版本过低）",
                mode,
                model,
                self.data_dir,
                count,
                photo_count,
            )
        )

    async def terminate(self):
        """插件被卸载/停用时调用。"""
        logger.info("[diet] 插件已停用，数据保留在 %s", self.data_dir)
