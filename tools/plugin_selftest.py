# -*- coding: utf-8 -*-
"""不依赖真实 AstrBot 环境的插件核心逻辑自测。"""
from __future__ import annotations

import datetime as dt
import importlib.util
import json
import sys
import tempfile
import time
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_MAIN = ROOT / "astrbot_plugin_diet" / "main.py"
TMP = ROOT / ".selftest_data"
# 每次运行都从干净目录开始，否则上一次的记录会被累加进汇总
if TMP.exists():
    import shutil
    shutil.rmtree(TMP)
TMP.mkdir(parents=True, exist_ok=True)
FENCE = chr(96) * 3

def _mod(name, **attrs):
    m = types.ModuleType(name)
    for k, v in attrs.items():
        setattr(m, k, v)
    sys.modules[name] = m
    parent, _, leaf = name.rpartition(".")
    if parent and parent in sys.modules:
        setattr(sys.modules[parent], leaf, m)
    return m

class _Logger:
    def __init__(self): self.records = []
    def _log(self, level, msg, *a): self.records.append((level, msg % a if a else msg))
    def info(self, msg, *a): self._log("INFO", msg, *a)
    def warning(self, msg, *a): self._log("WARN", msg, *a)
    def error(self, msg, *a, **k): self._log("ERROR", msg, *a)
    def debug(self, msg, *a): self._log("DEBUG", msg, *a)

logger = _Logger()

def _command(*_a, **_k):
    def deco(fn):
        fn.__is_command__ = True
        return fn
    return deco

class _AstrMessageEvent:
    def __init__(self, message_str="", umo="test:FriendMessage:u1"):
        self.message_str = message_str
        self.unified_msg_origin = umo
    def plain_result(self, text): return ("plain", text)
    def get_sender_name(self): return "tester"

class _Star:
    def __init__(self, context=None, config=None): self.context = context

class _Context: pass

_mod("astrbot")
_mod("astrbot.api", logger=logger)
_mod("astrbot.api.event", filter=types.SimpleNamespace(command=_command), AstrMessageEvent=_AstrMessageEvent)
_mod("astrbot.api.star", Context=_Context, Star=_Star)
_mod("astrbot.core")
_mod("astrbot.core.utils")
_mod("astrbot.core.utils.astrbot_path", get_astrbot_plugin_data_path=lambda: str(TMP))

try:
    import aiohttp  # noqa: F401
except ImportError:
    class _CT:
        def __init__(self, **k): pass
    class _CS:
        def __init__(self, **k): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *a): return False
    _mod("aiohttp", ClientTimeout=_CT, ClientSession=_CS)

spec = importlib.util.spec_from_file_location("diet_main", PLUGIN_MAIN)
diet = importlib.util.module_from_spec(spec)
sys.modules["diet_main"] = diet
spec.loader.exec_module(diet)

CONFIG = {
    "llm_mode": "openai_compatible",
    "base_url": "http://127.0.0.1:8000/v1",
    "model": "test-vl",
    "hmac_secret": "unit-test-secret",
    "timezone_offset_hours": 8,
    "remember_umo": True,
}
plugin = diet.DietPlugin(context=None, config=CONFIG)

PASS, FAIL = [], []

def check(name, cond, extra=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (("  <- " + str(extra)) if (extra and not cond) else ""))

print("== 1. 模型输出 JSON 解析 ==")
fenced = FENCE + 'json\n{"is_food": true, "title": "鸡胸沙拉", "calories_kcal": 420, "items": []}\n' + FENCE
r = diet.DietPlugin._parse_json(fenced)
check("解析被代码块包裹的 JSON", r.get("title") == "鸡胸沙拉" and r.get("calories_kcal") == 420, r)

r = diet.DietPlugin._parse_json('{"is_food": false, "reason": "不是食物"}')
check("解析纯 JSON", r.get("is_food") is False, r)

r = diet.DietPlugin._parse_json("模型今天心情不好，什么都不想说。")
check("垃圾输出降级为可展示结构", isinstance(r, dict) and "解析失败" in str(r.get("title")), r)

r = diet.DietPlugin._parse_json('分析如下：{"is_food":true,"title":"面条","calories_kcal":600} 以上。')
check("从混杂文本中抠出 JSON", r.get("title") == "面条", r)

r = diet.DietPlugin._parse_json("")
check("空输出不抛异常", isinstance(r, dict), r)

print()
print("== 2. 记录构造与营养汇总 ==")
now = dt.datetime(2026, 6, 27, 12, 30)
result = {
    "is_food": True, "title": "鸡胸沙拉", "meal": "午餐",
    "calories_kcal": "420.4", "protein_g": 35, "carbs_g": "20", "fat_g": 12.5,
    "items": [{"name": "鸡胸肉", "portion": "150g", "calories_kcal": 250}],
    "advice": "蛋白质充足", "confidence": 0.9, "_engine": "openai:test-vl",
}
rec = plugin._build_record(now, "2026-06-27", "120000_abcd.jpg", result)
check("数值字段被安全转成 float", rec["calories_kcal"] == 420.4 and rec["protein_g"] == 35.0, rec)
check("含 date/time/meal/photo", rec["date"] == "2026-06-27" and rec["time"] == "12:30" and rec["meal"] == "午餐")
check("保留模型引擎标记", rec["engine"] == "openai:test-vl")

bad = plugin._build_record(now, "2026-06-27", "x.jpg", {"is_food": True, "calories_kcal": "很多"})
check("非法数值降级为 0 而不是崩溃", bad["calories_kcal"] == 0.0, bad)

notfood = plugin._build_record(now, "2026-06-27", "y.jpg", {"is_food": False, "reason": "这是键盘"})
check("非食物记录被标记", notfood["is_food"] is False and notfood["title"] == "这是键盘", notfood)

plugin._append_record("2026-06-27", rec)
plugin._append_record("2026-06-27", notfood)
loaded = plugin._load_records("2026-06-27")
check("jsonl 写入后可读回", len(loaded) == 2, loaded)

totals = plugin._totals(loaded)
check("汇总时跳过非食物记录", totals["calories_kcal"] == 420.4, totals)
check("三大营养素汇总正确", totals["protein_g"] == 35.0 and totals["fat_g"] == 12.5, totals)

print()
print("== 3. HMAC 上传鉴权 ==")
future = int(time.time()) + 300
good = str(future) + "." + plugin._sign(future)

class _Req:
    def __init__(self, headers=None, query=None):
        self.headers = headers or {}
        self.query = query or {}
        self.method = "POST"
        self.path = "/x"
        self.plugin_name = "astrbot_plugin_diet"
        self.username = None

def authorized(token, secret=None):
    if secret:
        plugin.config["hmac_secret"] = secret
    diet.request = _Req({"X-Diet-Token": token} if token else {})
    try:
        return plugin._authorized()
    finally:
        plugin.config["hmac_secret"] = "unit-test-secret"

check("合法 token 通过", authorized(good) is True)
check("篡改签名被拒绝", authorized(str(future) + "." + "0" * 64) is False)
past = int(time.time()) - 10
check("过期 token 被拒绝", authorized(str(past) + "." + plugin._sign(past)) is False)
check("缺少 token 被拒绝", authorized("") is False)
check("乱格式 token 被拒绝", authorized("not-a-token") is False)
check("用别的密钥签的 token 被拒绝", authorized(good, secret="another-secret") is False)

print()
print("== 4. 路径穿越防护 ==")
check("正常文件名通过", str(plugin._safe_photo("2026-06-27", "120000_ab.jpg")).endswith("120000_ab.jpg"))
check("拒绝 ../ 穿越", plugin._safe_photo("2026-06-27", "../../secret.txt") is None)
check("拒绝绝对路径", plugin._safe_photo("2026-06-27", "C:\\Windows\\win.ini") is None)
check("拒绝非法日期", plugin._safe_photo("../../etc", "a.jpg") is None)
check("拒绝子目录", plugin._safe_photo("2026-06-27", "sub/a.jpg") is None)

print()
print("== 5. 餐次推断与日报渲染 ==")
def meal_at(h): return diet.DietPlugin._guess_meal(dt.datetime(2026, 6, 27, h, 0))
check("08:00 -> 早餐", meal_at(8) == "早餐", meal_at(8))
check("12:00 -> 午餐", meal_at(12) == "午餐", meal_at(12))
check("19:00 -> 晚餐", meal_at(19) == "晚餐", meal_at(19))
check("15:00 -> 加餐", meal_at(15) == "加餐", meal_at(15))
check("02:00 -> 夜宵", meal_at(2) == "夜宵", meal_at(2))

report = plugin._render_day("2026-06-27")
check("日报包含标题", "饮食日报" in report and "2026-06-27" in report, report)
check("日报包含热量合计", "420" in report, report)
check("日报包含营养素行", "蛋白" in report and "碳水" in report, report)
check("日报包含条目明细", "鸡胸肉" in report, report)
check("日报包含建议", "蛋白质充足" in report, report)

empty = plugin._render_day("2000-01-01")
check("无记录时给出友好提示", "还没有饮食记录" in empty, empty)

week = plugin._render_range(["2026-06-26", "2026-06-27"], "最近 2 天")
check("区间汇总包含日均", "日均" in week and "420" in week, week)

print()
print("== 6. 配置容错 ==")
plugin.config["extra_body"] = "{坏的 json"
check("非法 JSON 配置被忽略而不崩溃", plugin._json_conf("extra_body") == {})
plugin.config["extra_body"] = '{"top_p": 0.9}'
check("合法 JSON 配置被解析", plugin._json_conf("extra_body") == {"top_p": 0.9})
plugin.config.pop("extra_body", None)

try:
    p2 = diet.DietPlugin(context=None, config={"timezone_offset_hours": "不是数字"})
    check("非法时区降级为 UTC+8", p2.tz.utcoffset(None) == dt.timedelta(hours=8))
except Exception as exc:
    check("非法时区降级为 UTC+8", False, exc)

print()
print("== 7. 纯文字模式 ==")
import asyncio

captured = {}

async def _capture(path, prompt):
    captured["path"] = path
    captured["prompt"] = prompt
    return json.dumps({
        "is_food": True, "title": "牛肉面", "meal": "午餐",
        "calories_kcal": 620, "protein_g": 28, "carbs_g": 78, "fat_g": 18,
        "items": [{"name": "牛肉面", "portion": "1 碗", "calories_kcal": 620}],
        "advice": "汤别喝完，钠偏高。",
    }), "stub"

plugin._analyze_openai = _capture
res = asyncio.run(plugin._analyze(None, "中午吃了一碗牛肉面"))
check("纯文字模式不传图片", captured.get("path") is None, captured.get("path"))
check("纯文字模式使用文字提示词", "文字描述" in captured.get("prompt", ""), captured.get("prompt", "")[:80])
check("用户描述被拼进提示词", "牛肉面" in captured.get("prompt", ""))
check("纯文字结果解析正常", res.get("title") == "牛肉面", res)

res2 = asyncio.run(plugin._analyze(Path("fake.jpg") if False else None, ""))
check("无描述时不追加补充说明", "补充说明" not in captured.get("prompt", ""), captured.get("prompt", "")[-60:])

# 照片 + 备注：备注应进入提示词
captured.clear()
async def _capture_img(path, prompt):
    captured["path"] = path
    captured["prompt"] = prompt
    return json.dumps({"is_food": True, "title": "炒饭", "calories_kcal": 700}), "stub"
plugin._analyze_openai = _capture_img
(tmpimg := (TMP / "fake.jpg"))
tmpimg.write_bytes(b"fake")
asyncio.run(plugin._analyze(tmpimg, "这是我一个人吃的"))
check("照片模式使用图片提示词", "餐食照片" in captured.get("prompt", ""), captured.get("prompt", "")[:60])
check("照片模式带上补充说明", "一个人吃的" in captured.get("prompt", ""))
check("照片模式确实传了路径", captured.get("path") is not None)

rec_text = plugin._build_record(now, "2026-06-27", "", res, source="app-text", note="中午吃了一碗牛肉面")
check("文字记录 photo 为空", rec_text["photo"] == "")
check("文字记录带 source 标记", rec_text["source"] == "app-text")
check("文字记录保留原始描述", rec_text["note"] == "中午吃了一碗牛肉面")

plugin._append_record("2026-06-28", rec_text)
report_text = plugin._render_day("2026-06-28")
check("日报用 ✍️ 区分文字记录", "✍️" in report_text, report_text)
check("日报含文字记录标题", "牛肉面" in report_text, report_text)

report_img = plugin._render_day("2026-06-27")
check("日报用 📷 区分照片记录", "📷" in report_img, report_img)

print()
print("=" * 52)
print("通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    sys.exit(1)
print("全部通过 OK")
