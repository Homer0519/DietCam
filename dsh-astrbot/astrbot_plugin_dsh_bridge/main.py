# -*- coding: utf-8 -*-
"""astrbot_plugin_dsh_bridge

把公网 AstrBot 收到的消息转交给「本机 DeepSeek Harness」，并把 Harness 的
回复发回原来的会话。

方向：**本机 DSH 主动连公网 AstrBot**（HTTP 长轮询），所以 DSH 不需要公网入口，
放在 NAT 后面、家用宽带里都能用。

接口（全部挂在 /api/v1/plugins/extensions/astrbot_plugin_dsh_bridge/ 之下，
按 AstrBot 的规矩需要 plugin scope 的 API Key）：

    GET  /health                        健康检查 + 队列水位
    GET  /inbox?cursor=&timeout=&limit= 长轮询取件
    POST /reply  {"umo": "...", "text": "..."}   把消息发回会话
    POST /reset                          清空待取队列（调试）
    GET  /whoami                         回显调用方身份（排查鉴权用）

除 API Key 之外，每个请求还必须带第二把钥匙：

    X-DSH-Bridge-Token: "<exp>.<hex(hmac_sha256(secret, str(exp)))>"

两把钥匙分开存放：API Key 在 AstrBot 里，secret 在插件配置和 DSH 里。
"""

from __future__ import annotations

import asyncio
import base64
import datetime as dt
import hashlib
import hmac
import time
from collections import deque
from pathlib import Path
from typing import Any

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star
from astrbot.core.utils.astrbot_path import get_astrbot_plugin_data_path

try:  # 4.18+ 才有插件 Web API
    from astrbot.api.web import error_response, json_response, request

    WEB_API = True
except Exception:  # pragma: no cover - 老版本兼容
    error_response = json_response = None  # type: ignore
    request = None  # type: ignore
    WEB_API = False

try:
    from astrbot.api.event import MessageChain
except Exception:  # pragma: no cover
    MessageChain = None  # type: ignore

try:
    from astrbot.api.message_components import Plain
except Exception:  # pragma: no cover
    Plain = None  # type: ignore

try:
    from astrbot.api.event.filter import EventMessageType
except Exception:  # pragma: no cover - 用兜底值让装饰器仍可被导入
    class EventMessageType:  # type: ignore[no-redef]
        ALL = None


PLUGIN_NAME = "astrbot_plugin_dsh_bridge"
PLUGIN_VERSION = "1.0.0"
TOKEN_HEADER = "X-DSH-Bridge-Token"

# 轮询间隔：长轮询内部的自旋步长。0.2s 足够灵敏，又完全不会压到 CPU。
POLL_STEP_SEC = 0.2

_MEDIA_MAGIC = (
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"GIF87a", "image/gif"),
    (b"GIF89a", "image/gif"),
)
_SUFFIX_MEDIA = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".bmp": "image/bmp",
}


# --------------------------------------------------------------------- 工具


def _pick(mapping: Any, key: str, default: Any = None) -> Any:
    """从请求的映射对象里安全取值。

    AstrBot 的 PluginMultiDict（query / form / files 的返回类型）**不是 dict 的
    子类**，所以不能用 isinstance(x, dict) 判断，否则会静默取不到值。
    这里统一走鸭子类型。
    """
    if mapping is None:
        return default
    getter = getattr(mapping, "get", None)
    if not callable(getter):
        return default
    try:
        value = getter(key, default)
    except TypeError:
        try:
            value = getter(key)
        except Exception:  # noqa: BLE001
            return default
    except Exception:  # noqa: BLE001
        return default
    return default if value is None else value


def _as_int(value: Any, default: int) -> int:
    try:
        if value is None or value == "":
            return default
        return int(value)
    except (TypeError, ValueError):
        return default


def _as_float(value: Any, default: float) -> float:
    try:
        if value is None or value == "":
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _guess_media_type(path: Any, raw: bytes) -> str:
    for magic, media in _MEDIA_MAGIC:
        if raw.startswith(magic):
            return media
    if raw[:4] == b"RIFF" and raw[8:12] == b"WEBP":
        return "image/webp"
    if isinstance(path, str):
        media = _SUFFIX_MEDIA.get(Path(path).suffix.lower())
        if media:
            return media
    return "image/jpeg"


def split_text(text: str, limit: int) -> list[str]:
    """把长回复按段落/句子边界切成多条，避免超出平台单条消息长度限制。"""
    text = (text or "").strip()
    if not text:
        return []
    if limit <= 0 or len(text) <= limit:
        return [text]
    chunks: list[str] = []
    remaining = text
    while len(remaining) > limit:
        window = remaining[:limit]
        cut = -1
        for sep in ("\n\n", "\n", "。", "！", "？", ". ", " "):
            cut = max(cut, window.rfind(sep) + (len(sep) if sep != "\n\n" else 0))
        if cut <= 0 or cut < limit // 3:
            cut = limit
        chunks.append(remaining[:cut].strip())
        remaining = remaining[cut:].lstrip()
    if remaining:
        chunks.append(remaining)
    return [c for c in chunks if c]


def _safe_call(obj: Any, name: str, default: Any = None, *args: Any) -> Any:
    fn = getattr(obj, name, None)
    if not callable(fn):
        return default
    try:
        return fn(*args)
    except Exception:  # noqa: BLE001
        return default


# --------------------------------------------------------------------- 插件


class DshBridgePlugin(Star):
    def __init__(self, context: Context, config: Any = None):
        super().__init__(context)
        try:
            self.config = dict(config) if config else {}
        except Exception:  # noqa: BLE001
            self.config = {}

        self.data_dir = Path(get_astrbot_plugin_data_path()) / PLUGIN_NAME
        try:
            self.data_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:  # pragma: no cover
            logger.warning("[dsh-bridge] 无法创建数据目录 %s: %s", self.data_dir, exc)

        # 待取队列：元素为 dict，每个带单调递增的 cursor
        self._queue: deque[dict] = deque()
        self._cursor = 0            # 已分配的最大序号
        self._delivered = 0         # 已被 /inbox 取走的最大序号
        self._received = 0
        self._sent = 0
        self._send_failed = 0
        self._dropped = 0
        self._started_at = int(time.time())

        if WEB_API:
            self._register_web_apis()
        else:  # pragma: no cover
            logger.warning(
                "[dsh-bridge] 当前 AstrBot 版本没有 plugin Web API，DSH 无法取件"
            )

        if not self._cfg_str("hmac_secret"):
            logger.warning(
                "[dsh-bridge] 还没设置 hmac_secret，所有接口都会拒绝请求。"
                "请在插件配置里填一个随机串（DSH 侧填同一个）。"
            )
        logger.info("[dsh-bridge] 数据目录: %s", self.data_dir)

    # ------------------------------------------------------------ 配置读取

    def _cfg(self, key: str, default: Any) -> Any:
        value = self.config.get(key)
        return default if value is None or value == "" else value

    def _cfg_bool(self, key: str, default: bool) -> bool:
        value = self.config.get(key)
        if value is None or value == "":
            return default
        if isinstance(value, bool):
            return value
        return str(value).strip().lower() in ("1", "true", "yes", "on")

    def _cfg_int(self, key: str, default: int) -> int:
        return _as_int(self.config.get(key), default)

    def _cfg_str(self, key: str, default: str = "") -> str:
        value = self.config.get(key)
        return default if value is None else str(value)

    # ---------------------------------------------------------------- 注册

    def _register_web_apis(self) -> None:
        prefix = "/" + PLUGIN_NAME
        routes = [
            (prefix + "/health", self.api_health, ["GET"], "dsh bridge health"),
            (prefix + "/inbox", self.api_inbox, ["GET"], "long-poll pending events"),
            (prefix + "/reply", self.api_reply, ["POST"], "send a message back to a session"),
            (prefix + "/reset", self.api_reset, ["POST"], "drop every pending event"),
            (prefix + "/whoami", self.api_whoami, ["GET"], "echo caller identity"),
        ]
        for route, handler, methods, desc in routes:
            self.context.register_web_api(route, handler, methods, desc)
        logger.info("[dsh-bridge] 已注册 %d 个 Web API", len(routes))

    # ---------------------------------------------------------------- 鉴权

    def _sign(self, exp: int) -> str:
        secret = self._cfg_str("hmac_secret")
        return hmac.new(
            secret.encode("utf-8"), str(exp).encode("utf-8"), hashlib.sha256
        ).hexdigest()

    def _authorized(self) -> bool:
        """校验 X-DSH-Bridge-Token: "<exp>.<hmac_sha256(secret, exp)>"。"""
        if not self._cfg_str("hmac_secret"):
            return False
        try:
            token = str(request.headers.get(TOKEN_HEADER) or "").strip()
        except Exception:  # noqa: BLE001
            return False
        if "." not in token:
            return False
        raw_exp, sig = token.split(".", 1)
        exp = _as_int(raw_exp, 0)
        if exp <= 0 or exp < int(time.time()):
            return False
        return hmac.compare_digest(sig.strip().lower(), self._sign(exp))

    # ------------------------------------------------------------ 收件入口

    @filter.event_message_type(EventMessageType.ALL)
    async def on_any_message(self, event: AstrMessageEvent) -> None:
        """观察所有平台的入站消息；符合条件时排队给 DSH。"""
        try:
            await self._handle_inbound(event)
        except Exception as exc:  # noqa: BLE001 - 绝不能让插件异常打断消息管线
            logger.error("[dsh-bridge] 处理入站消息出错: %s", exc, exc_info=True)

    async def _handle_inbound(self, event: AstrMessageEvent) -> None:
        if not self._cfg_bool("enabled", True):
            return

        mentioned = self._mentioned(event)
        is_private = bool(_safe_call(event, "is_private_chat", False))
        mode = self._cfg_str("forward_mode", "direct")
        forward = True if mode == "all" else (is_private or mentioned)
        if not forward:
            return

        umo = str(getattr(event, "unified_msg_origin", "") or "")
        if not umo:
            return

        text = str(getattr(event, "message_str", "") or "").strip()
        images, notes = await self._collect_images(event)
        if not text and not images:
            return

        # 接管：既然后面 DSH 会回复，就别让 AstrBot 自己的大模型再回一遍
        if self._cfg_bool("suppress_default_llm", True):
            _safe_call(event, "should_call_llm", None, True)
        if self._cfg_bool("stop_event", False):
            _safe_call(event, "stop_event", None)

        ts = int(time.time())
        self._enqueue(
            {
                "kind": "message",
                "umo": umo,
                "platform": str(_safe_call(event, "get_platform_id", "") or ""),
                "platform_name": str(_safe_call(event, "get_platform_name", "") or ""),
                "text": text,
                "sender_id": str(_safe_call(event, "get_sender_id", "") or ""),
                "sender_name": str(_safe_call(event, "get_sender_name", "") or ""),
                "group_id": str(_safe_call(event, "get_group_id", "") or ""),
                "is_private": is_private,
                "mentioned": mentioned,
                "images": images,
                "notes": notes,
                "ts": ts,
                "time": dt.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S"),
            }
        )
        if self._cfg_bool("debug", False):
            logger.info(
                "[dsh-bridge] 入队 umo=%s private=%s at=%s text=%r images=%d",
                umo, is_private, mentioned, text[:80], len(images),
            )

    def _mentioned(self, event: AstrMessageEvent) -> bool:
        """这条消息是否 @ 了机器人（AtAll 也算）。"""
        try:
            self_id = str(event.get_self_id() or "")
        except Exception:  # noqa: BLE001
            self_id = ""
        components = _safe_call(event, "get_messages", []) or []
        try:
            iterator = list(components)
        except TypeError:
            return False
        for comp in iterator:
            name = type(comp).__name__
            if name == "AtAll":
                return True
            if name != "At":
                continue
            qq = str(getattr(comp, "qq", "") or "")
            if qq and (qq == self_id or qq == "all"):
                return True
        return False

    async def _collect_images(self, event: AstrMessageEvent) -> tuple[list[dict], list[str]]:
        if not self._cfg_bool("forward_images", True):
            return [], []
        limit = max(0, self._cfg_int("max_images", 4))
        max_bytes = max(0, self._cfg_int("max_image_bytes", 8 * 1024 * 1024))
        components = _safe_call(event, "get_messages", []) or []
        try:
            iterator = list(components)
        except TypeError:
            return [], []

        images: list[dict] = []
        notes: list[str] = []
        for comp in iterator:
            if type(comp).__name__ != "Image":
                continue
            if len(images) >= limit:
                notes.append("图片数量超过上限，后面的没有转发")
                break
            raw, media = await self._read_image(comp, max_bytes)
            if raw is None:
                notes.append("有一张图片读取失败或超过体积上限，没有转发")
                continue
            images.append(
                {"mediaType": media, "base64": base64.b64encode(raw).decode("ascii")}
            )
        return images, notes

    async def _read_image(self, comp: Any, max_bytes: int) -> tuple[bytes | None, str]:
        """取图片原始字节。

        AstrBot 的 PreProcessStage 通常已经把 Image.file / .path 换成了本地绝对
        路径，所以优先直接读盘；读不到再退回组件自带的 base64 转换。
        """
        path = getattr(comp, "path", None) or getattr(comp, "file", None)
        raw: bytes | None = None
        if isinstance(path, str) and path and "://" not in path and not path.startswith("data:"):
            candidate = Path(path)
            try:
                if candidate.is_file() and candidate.stat().st_size <= max_bytes:
                    raw = candidate.read_bytes()
            except OSError:
                raw = None

        if raw is None:
            converter = getattr(comp, "convert_to_base64", None)
            if callable(converter):
                try:
                    encoded = await converter()
                except Exception:  # noqa: BLE001
                    encoded = None
                if isinstance(encoded, str) and encoded:
                    try:
                        decoded = base64.b64decode(encoded, validate=False)
                    except Exception:  # noqa: BLE001
                        decoded = b""
                    if decoded and len(decoded) <= max_bytes:
                        raw = decoded

        if not raw:
            return None, ""
        return raw, _guess_media_type(path, raw)

    # -------------------------------------------------------------- 队列

    def _enqueue(self, payload: dict) -> None:
        self._cursor += 1
        payload["cursor"] = self._cursor
        payload["id"] = "evt-%d-%d" % (payload.get("ts", 0), self._cursor)
        self._queue.append(payload)
        self._received += 1
        max_queue = max(1, self._cfg_int("max_queue", 200))
        while len(self._queue) > max_queue:
            self._queue.popleft()
            self._dropped += 1
            logger.warning("[dsh-bridge] 队列已满，丢弃最旧的未取事件")

    def _pending(self, since: int, limit: int) -> list[dict]:
        out: list[dict] = []
        for item in self._queue:
            if item.get("cursor", 0) > since:
                out.append(item)
                if len(out) >= limit:
                    break
        return out

    def _prune(self) -> None:
        while self._queue and self._queue[0].get("cursor", 0) <= self._delivered:
            self._queue.popleft()

    def _stats(self) -> dict:
        return {
            "cursor": self._cursor,
            "delivered": self._delivered,
            "queued": len(self._queue),
            "received": self._received,
            "sent": self._sent,
            "send_failed": self._send_failed,
            "dropped": self._dropped,
            "uptime_sec": int(time.time()) - self._started_at,
        }

    # ------------------------------------------------------------ Web API

    async def api_health(self):
        return json_response(
            {
                "ok": True,
                "plugin": PLUGIN_NAME,
                "version": PLUGIN_VERSION,
                "server_time": int(time.time()),
                "data_dir": str(self.data_dir),
                "enabled": self._cfg_bool("enabled", True),
                "forward_mode": self._cfg_str("forward_mode", "direct"),
                "features": {
                    "inbox": True,
                    "reply": True,
                    "images": self._cfg_bool("forward_images", True),
                    "stop_event": self._cfg_bool("stop_event", False),
                },
                **self._stats(),
            }
        )

    async def api_whoami(self):
        if not self._authorized():
            return error_response("unauthorized", status_code=401)
        try:
            key = str(request.headers.get("Authorization") or "")
            client = str(getattr(request, "client_host", "") or "")
        except Exception:  # noqa: BLE001
            key, client = "", ""
        return json_response(
            {
                "ok": True,
                "plugin": PLUGIN_NAME,
                "client_host": client,
                "api_key_seen": bool(key),
                **self._stats(),
            }
        )

    async def api_inbox(self):
        if not self._authorized():
            return error_response("unauthorized", status_code=401)

        since = _as_int(_pick(request.query, "cursor", 0), 0)
        timeout = _as_float(_pick(request.query, "timeout", 25), 25.0)
        max_wait = max(0, self._cfg_int("max_poll_seconds", 55))
        timeout = max(0.0, min(timeout, float(max_wait)))
        limit = max(1, min(_as_int(_pick(request.query, "limit", 20), 20), 100))

        events = self._pending(since, limit)
        if not events and timeout > 0:
            deadline = time.time() + timeout
            while not events and time.time() < deadline:
                await asyncio.sleep(POLL_STEP_SEC)
                events = self._pending(since, limit)

        next_cursor = events[-1]["cursor"] if events else since
        if next_cursor > self._delivered:
            self._delivered = next_cursor
            self._prune()

        return json_response(
            {
                "ok": True,
                "cursor": next_cursor,
                "head": self._cursor,
                "events": events,
                "more": len(events) >= limit,
            }
        )

    async def api_reply(self):
        if not self._authorized():
            return error_response("unauthorized", status_code=401)

        try:
            payload = await request.json(default={})
        except Exception:  # noqa: BLE001
            payload = {}
        if not isinstance(payload, dict):
            payload = {}

        umo = str(payload.get("umo") or "").strip()
        text = payload.get("text")
        if not umo:
            return error_response("umo 不能为空")
        if not isinstance(text, str) or not text.strip():
            return error_response("text 不能为空")

        chunks = split_text(text, self._cfg_int("max_message_chars", 1500))
        if not chunks:
            return error_response("切分后没有可发送的内容")

        sent = 0
        for chunk in chunks:
            if not await self._send(umo, chunk):
                self._send_failed += 1
                return json_response(
                    {
                        "ok": False,
                        "sent": sent,
                        "chunks": len(chunks),
                        "error": "send_message 失败：平台不在线或 umo 不合法",
                    }
                )
            sent += 1
        self._sent += sent
        return json_response({"ok": True, "sent": sent, "chunks": len(chunks)})

    async def api_reset(self):
        if not self._authorized():
            return error_response("unauthorized", status_code=401)
        cleared = len(self._queue)
        self._queue.clear()
        self._delivered = self._cursor
        # 注意：不要用 dropped 这个键名——_stats() 里也有 dropped（累计丢弃数），
        # 展开在后面会把它覆盖掉，调用方看到的永远是累计值。
        return json_response({"ok": True, "cleared": cleared, **self._stats()})

    async def _send(self, umo: str, text: str) -> bool:
        if MessageChain is None or Plain is None:
            logger.error("[dsh-bridge] 当前 AstrBot 缺少 MessageChain / Plain，无法发送")
            return False
        try:
            chain = MessageChain([Plain(text)])
        except Exception:  # noqa: BLE001 - 兼容旧版构造方式
            try:
                chain = MessageChain().message(text)
            except Exception as exc:  # noqa: BLE001
                logger.error("[dsh-bridge] 构造消息链失败: %s", exc)
                return False
        try:
            result = await self.context.send_message(umo, chain)
        except Exception as exc:  # noqa: BLE001
            logger.error("[dsh-bridge] 发送失败 umo=%s: %s", umo, exc)
            return False
        return True if result is None else bool(result)

    # -------------------------------------------------------------- 指令

    @filter.command("dsh状态")
    async def dsh_status(self, event: AstrMessageEvent):
        """查看 DSH 桥接状态（队列水位、已收发条数）。"""
        stats = self._stats()
        lines = [
            "DSH 桥接状态",
            "共享密钥：%s" % ("已设置" if self._cfg_str("hmac_secret") else "**未设置**"),
            "转发模式：%s" % self._cfg_str("forward_mode", "direct"),
            "待取事件：%d（已分配序号 %d，已取走 %d）"
            % (stats["queued"], stats["cursor"], stats["delivered"]),
            "累计：收 %d / 发 %d / 发送失败 %d / 丢弃 %d"
            % (stats["received"], stats["sent"], stats["send_failed"], stats["dropped"]),
            "运行时长：%d 秒" % stats["uptime_sec"],
        ]
        yield event.plain_result("\n".join(lines))
