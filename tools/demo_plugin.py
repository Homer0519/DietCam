# -*- coding: utf-8 -*-
"""DietCam 插件端到端演示。

在内存里搭一个最小可用的 AstrBot 环境（含 astrbot.api.web 的请求/响应对象），
然后像真实客户端那样调用插件的 Web API：

    1. GET  /health    健康检查
    2. POST /analyze   上传一张真实的炒饭照片（会真的写进磁盘）
    3. GET  /records   查询当天记录
    4. GET  /photo     取回归档照片
    5. 伪造签名 -> 应被拒绝
    6. 聊天里 /饮食 的输出

视觉模型用桩函数替代（返回一份扬州炒饭的营养估计），
所以这个脚本不需要任何模型服务也能跑。
"""
from __future__ import annotations

import asyncio
import datetime as dt
import importlib.util
import json
import struct
import sys
import types
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_MAIN = ROOT / "astrbot_plugin_diet" / "main.py"
DEMO_DIR = ROOT / ".demo_data"
PHOTO_IN = ROOT / ".demo_data" / "chao_fan.png"


# ----------------------------------------------------------------- 造一张图

def make_png(width: int, height: int) -> bytes:
    """纯 Python 生成一张"一盘炒饭"的 PNG，不需要 Pillow。"""
    rows = []
    cx, cy, r = width / 2, height / 2, min(width, height) * 0.40
    for y in range(height):
        row = bytearray()
        for x in range(width):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy < r * r:
                # 盘子里的炒饭：米黄底色 + 青豆 + 胡萝卜丁 + 鸡蛋
                seed = (x * 7919 + y * 104729) % 1000
                if seed < 40:
                    px = (86, 150, 60)      # 青豆
                elif seed < 75:
                    px = (226, 140, 46)     # 胡萝卜
                elif seed < 110:
                    px = (250, 214, 96)     # 鸡蛋
                else:
                    px = (236, 205, 138)    # 米饭
            elif dx * dx + dy * dy < (r + 10) ** 2:
                px = (238, 238, 240)        # 盘沿
            else:
                px = (58, 46, 36)           # 桌面
            row += bytes(px)
        rows.append(b"\x00" + bytes(row))
    raw = b"".join(rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


# --------------------------------------------------- 伪造 astrbot.api.web

class Resp:
    def __init__(self, kind, payload, status):
        self.kind, self.payload, self.status_code = kind, payload, status
    def __repr__(self):
        return "<%s %s>" % (self.status_code, self.kind)

def json_response(data=None, *, status_code=200, **kw):
    return Resp("json", {} if data is None else data, status_code)

def error_response(message, *, status_code=400, data=None, **kw):
    return Resp("error", {"message": message, "data": data}, status_code)

def file_response(path, *, filename=None, content_type=None, **kw):
    return Resp("file", str(path), 200)

class _Query:
    def __init__(self, d): self._d = dict(d or {})
    def get(self, key, default=None, type=None):
        v = self._d.get(key, default)
        if type is not None and v is not None:
            try: return type(v)
            except (TypeError, ValueError): return default
        return v

class _Upload:
    def __init__(self, filename, data):
        self.filename, self._data = filename, data
    async def save(self, destination):
        Path(destination).write_bytes(self._data)

class _Request:
    def __init__(self, headers=None, query=None, files=None, form=None, payload=None):
        self.headers = dict(headers or {})
        self.query = _Query(query)
        self._files = dict(files or {})
        self._form = dict(form or {})
        self._payload = payload
        self.method, self.path, self.plugin_name, self.username = "POST", "/demo", "astrbot_plugin_diet", "demo"
    async def files(self): return self._files
    async def form(self): return self._form
    async def json(self, default=None):
        return self._payload if self._payload is not None else (default if default is not None else {})

def create_environment():
    def _mod(name, **attrs):
        m = types.ModuleType(name)
        for k, v in attrs.items(): setattr(m, k, v)
        sys.modules[name] = m
        parent, _, leaf = name.rpartition(".")
        if parent and parent in sys.modules: setattr(sys.modules[parent], leaf, m)
        return m

    class _Logger:
        def __init__(self): self.lines = []
        def _f(self, lvl, msg, *a):
            text = msg % a if a else msg
            self.lines.append((lvl, text))
        def info(self, m, *a): self._f("INFO", m, *a)
        def warning(self, m, *a): self._f("WARN", m, *a)
        def error(self, m, *a, **k): self._f("ERROR", m, *a)
        def debug(self, m, *a): self._f("DEBUG", m, *a)

    logger = _Logger()

    def _command(*_a, **_k):
        def deco(fn):
            fn.__is_command__ = True
            return fn
        return deco

    class _Event:
        def __init__(self, message_str, umo="webchat:FriendMessage:demo"):
            self.message_str, self.unified_msg_origin = message_str, umo
        def plain_result(self, text): return ("plain", text)

    class _Star:
        def __init__(self, context=None, config=None): self.context = context
    class _Ctx:
        """假的 AstrBot Context，记录插件注册的 Web API。"""
        def __init__(self): self.registered = []
        def register_web_api(self, route, handler, methods, desc):
            self.registered.append((route, methods, desc))

    _mod("astrbot")
    _mod("astrbot.api", logger=logger)
    _mod("astrbot.api.event", filter=types.SimpleNamespace(command=_command), AstrMessageEvent=_Event)
    _mod("astrbot.api.star", Context=_Ctx, Star=_Star)
    globals()["_FakeContext"] = _Ctx
    _mod("astrbot.api.web", json_response=json_response, error_response=error_response,
         file_response=file_response, request=None)
    _mod("astrbot.core")
    _mod("astrbot.core.utils")
    _mod("astrbot.core.utils.astrbot_path", get_astrbot_plugin_data_path=lambda: str(DEMO_DIR))

    # 演示里不发真实网络请求，缺 aiohttp 时用占位实现
    try:
        import aiohttp  # noqa: F401
    except ImportError:
        class _Timeout:
            def __init__(self, **kw): pass
        class _Session:
            def __init__(self, **kw): pass
            async def __aenter__(self): return self
            async def __aexit__(self, *a): return False
        _mod("aiohttp", ClientTimeout=_Timeout, ClientSession=_Session)
    return logger


# ------------------------------------------------------------ 视觉模型桩

FRIED_RICE = {
    "is_food": True,
    "title": "扬州炒饭配紫菜蛋花汤",
    "meal": "午餐",
    "items": [
        {"name": "米饭", "portion": "约 250g", "calories_kcal": 330, "protein_g": 6.5, "carbs_g": 72.0, "fat_g": 1.0},
        {"name": "鸡蛋", "portion": "1 个", "calories_kcal": 70, "protein_g": 6.0, "carbs_g": 0.5, "fat_g": 5.0},
        {"name": "虾仁", "portion": "约 40g", "calories_kcal": 45, "protein_g": 9.0, "carbs_g": 0.5, "fat_g": 0.5},
        {"name": "火腿丁", "portion": "约 30g", "calories_kcal": 60, "protein_g": 4.0, "carbs_g": 1.0, "fat_g": 4.0},
        {"name": "青豆胡萝卜", "portion": "约 40g", "calories_kcal": 25, "protein_g": 2.0, "carbs_g": 4.0, "fat_g": 0.2},
        {"name": "食用油", "portion": "约 15g", "calories_kcal": 135, "protein_g": 0.0, "carbs_g": 0.0, "fat_g": 15.0},
        {"name": "紫菜蛋花汤", "portion": "1 碗", "calories_kcal": 55, "protein_g": 4.0, "carbs_g": 3.0, "fat_g": 2.5},
    ],
    "calories_kcal": 720,
    "protein_g": 31.5,
    "carbs_g": 81.0,
    "fat_g": 28.2,
    "confidence": 0.82,
    "advice": "碳水偏高、蛋白质略少。下次米饭减到 150g，再配一份清炒时蔬会更均衡。",
}

SNACK = {
    "is_food": True,
    "title": "无糖希腊酸奶配坚果",
    "meal": "加餐",
    "items": [
        {"name": "无糖希腊酸奶", "portion": "150g", "calories_kcal": 110, "protein_g": 15.0, "carbs_g": 6.0, "fat_g": 3.0},
        {"name": "混合坚果", "portion": "约 15g", "calories_kcal": 95, "protein_g": 3.0, "carbs_g": 3.0, "fat_g": 8.5},
    ],
    "calories_kcal": 205,
    "protein_g": 18.0,
    "carbs_g": 9.0,
    "fat_g": 11.5,
    "confidence": 0.88,
    "advice": "很好的加餐，蛋白质够、升糖低。",
}


_FakeContext = None


async def main() -> int:
    logger = create_environment()
    spec = importlib.util.spec_from_file_location("diet_main", PLUGIN_MAIN)
    diet = importlib.util.module_from_spec(spec)
    sys.modules["diet_main"] = diet
    spec.loader.exec_module(diet)

    if DEMO_DIR.exists():
        import shutil
        shutil.rmtree(DEMO_DIR)
    DEMO_DIR.mkdir(parents=True, exist_ok=True)

    CONFIG = {
        "llm_mode": "openai_compatible",
        "base_url": "http://127.0.0.1:8000/v1",
        "model": "qwen2.5-vl-7b-instruct",
        "hmac_secret": "demo-secret",
        "timezone_offset_hours": 8,
        "analyze_prompt": "",
    }
    fake_context = _FakeContext()
    plugin = diet.DietPlugin(context=fake_context, config=CONFIG)
    print("插件启动时注册的 Web API：")
    for route, methods, desc in fake_context.registered:
        print("    %-4s %-46s %s" % (",".join(methods), route, desc))
    print()

    # 用桩函数替换真实的模型请求
    queue = [FRIED_RICE, SNACK]
    async def fake_analyze(path: Path, prompt: str):
        await asyncio.sleep(0)
        return json.dumps(queue.pop(0), ensure_ascii=False), "openai:qwen2.5-vl-7b-instruct (stub)"
    plugin._analyze_openai = fake_analyze

    def set_request(**kw):
        diet.request = _Request(**kw)

    def auth_headers():
        exp = int(dt.datetime.now().timestamp()) + 300
        return {"X-Diet-Token": "%d.%s" % (exp, plugin._sign(exp))}

    line = "=" * 66
    print(line)
    print(" DietCam 插件演示 —— 在模拟 AstrBot 环境里端到端跑一遍")
    print(line)

    # ---------------------------------------------------------- 1. health
    print()
    print("[1] GET /health   健康检查")
    set_request(headers=auth_headers())
    resp = await plugin.api_health()
    print("    HTTP %d" % resp.status_code)
    print("    " + json.dumps(resp.payload, ensure_ascii=False))

    # --------------------------------------------------------- 2. analyze
    print()
    print("[2] POST /analyze   上传一张炒饭照片")
    png = make_png(320, 320)
    PHOTO_IN.write_bytes(png)
    print("    生成测试图片 chao_fan.png（%d 字节，纯 Python 合成的一盘炒饭）" % len(png))

    set_request(
        headers=auth_headers(),
        files={"file": _Upload("chao_fan.png", png)},
        form={"note": "午饭，外卖"},
    )
    t0 = dt.datetime.now()
    resp = await plugin.api_analyze()
    cost = (dt.datetime.now() - t0).total_seconds()
    print("    HTTP %d   耗时 %.2fs" % (resp.status_code, cost))
    if resp.status_code != 200:
        print("    失败：" + json.dumps(resp.payload, ensure_ascii=False))
        return 1
    rec = resp.payload["record"]
    print()
    print("    识别结果：")
    print("      餐次      %s" % rec["meal"])
    print("      标题      %s" % rec["title"])
    print("      热量      %g kcal" % rec["calories_kcal"])
    print("      蛋白质    %g g" % rec["protein_g"])
    print("      碳水      %g g" % rec["carbs_g"])
    print("      脂肪      %g g" % rec["fat_g"])
    print("      置信度    %g" % rec["confidence"])
    print("      建议      %s" % rec["advice"])
    print("      逐项：")
    for it in rec["items"]:
        print("        · %-12s %-10s %4d kcal" % (it["name"], it.get("portion", ""), it["calories_kcal"]))

    # 再加一条加餐，让日报更有内容
    set_request(headers=auth_headers(),
                files={"file": _Upload("snack.png", make_png(240, 240))},
                form={"note": ""})
    await plugin.api_analyze()

    # --------------------------------------------------------- 3. records
    day = dt.datetime.now(plugin.tz).strftime("%Y-%m-%d")
    print()
    print("[3] GET /records?date=%s   查询当天记录" % day)
    set_request(headers=auth_headers(), query={"date": day})
    resp = await plugin.api_records()
    print("    HTTP %d" % resp.status_code)
    print("    条数 %d" % resp.payload["count"])
    print("    合计 " + json.dumps(resp.payload["totals"], ensure_ascii=False))

    # ----------------------------------------------------------- 4. photo
    print()
    print("[4] GET /photo   取回归档照片")
    photo_name = rec["photo"]
    set_request(headers=auth_headers(), query={"date": day, "name": photo_name})
    resp = await plugin.api_photo()
    p = Path(resp.payload)
    print("    HTTP %d  ->  %s (%d 字节)" % (resp.status_code, p.name, p.stat().st_size if p.exists() else -1))

    # -------------------------------------------------------- 5. 安全性
    print()
    print("[5] 安全校验")
    set_request(headers={"X-Diet-Token": "9999999999." + "0" * 64},
                files={"file": _Upload("x.png", png)})
    resp = await plugin.api_analyze()
    print("    伪造签名 -> HTTP %d  %s" % (resp.status_code, resp.payload["message"]))

    set_request(headers={}, query={"date": day})
    resp = await plugin.api_records()
    print("    无签名   -> HTTP %d  %s" % (resp.status_code, resp.payload["message"]))

    set_request(headers=auth_headers(), query={"date": day, "name": "../../../main.py"})
    resp = await plugin.api_photo()
    print("    路径穿越 -> HTTP %d  %s" % (resp.status_code, resp.payload["message"]))

    # -------------------------------------------------- 6. 聊天指令输出
    class _Ev:
        def __init__(self, s):
            self.message_str, self.unified_msg_origin = s, "webchat:FriendMessage:demo"
        def plain_result(self, t): return ("plain", t)

    async def run_cmd(text):
        out = []
        async for item in plugin.cmd_diet(_Ev(text)):
            out.append(item[1])
        return "\n".join(out)

    for cmd in ["/饮食", "/饮食 本周"]:
        print()
        print("[6] 聊天里发送 %s   输出：" % cmd)
        print("    " + "-" * 58)
        for ln in (await run_cmd(cmd)).splitlines():
            print("    " + ln)
        print("    " + "-" * 58)

    # ------------------------------------------------------ 7. 磁盘结构
    print()
    print("[7] 服务器上落盘的文件（这就是【不进聊天上下文】的那份数据）")
    for path in sorted(DEMO_DIR.rglob("*")):
        if path.is_file():
            rel = path.relative_to(DEMO_DIR)
            print("    %-46s %7d B" % (rel, path.stat().st_size))

    print()
    print("[8] 记录文件的一行原始内容（records/%s.jsonl）" % day)
    first = (DEMO_DIR / "astrbot_plugin_diet" / "records" / (day + ".jsonl")).read_text(encoding="utf-8").splitlines()[0]
    print("    " + json.dumps(json.loads(first), ensure_ascii=False)[:300] + " ...")

    print()
    print(line)
    print(" 全过程没有产生任何聊天消息 —— 记录只落盘，需要时才用 /饮食 取出来看。")
    print(" 插件日志：")
    for lvl, text in logger.lines:
        print("    [%s] %s" % (lvl, text))
    print(line)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
