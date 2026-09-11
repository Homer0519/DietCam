# -*- coding: utf-8 -*-
"""astrbot_plugin_diet 的自动化测试。

用 tools/astrbot_stub.py 搭出忠实的 AstrBot 环境（关键是 PluginMultiDict
不是 dict 子类），然后从「注册的路由」这一层真正调用插件的 Web API，
而不是绕过请求对象直接调内部方法。

运行：
    python tools/plugin_selftest.py
"""
from __future__ import annotations

import asyncio
import datetime as dt
import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import astrbot_stub as stub  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TMP = ROOT / ".selftest_data"
if TMP.exists():
    shutil.rmtree(TMP)
TMP.mkdir(parents=True, exist_ok=True)

CONFIG = {
    "llm_mode": "openai_compatible",
    "base_url": "http://127.0.0.1:8000/v1",
    "model": "test-vl",
    "hmac_secret": "unit-test-secret",
    "timezone_offset_hours": 8,
    "remember_umo": True,
}

logger, _boot_context = stub.install(TMP)
module, plugin, context = stub.load_plugin(CONFIG, module_name="diet_under_test")

PASS: list[str] = []
FAIL: list[str] = []


def check(name: str, cond: bool, extra: object = "") -> None:
    (PASS if cond else FAIL).append(name)
    mark = "  [PASS] " if cond else "  [FAIL] "
    tail = ("   <- " + str(extra)) if (extra and not cond) else ""
    print(mark + name + tail)


def section(title: str) -> None:
    print()
    print("== " + title + " ==")


# ---------------------------------------------------------------- 测试工具

def auth(token: str | None = None) -> dict:
    if token is not None:
        return {"X-Diet-Token": token}
    return stub.auth_headers(plugin)


def make_request(**kw) -> stub.PluginRequest:
    headers = dict(auth())
    headers.update(kw.pop("headers", {}) or {})
    req = stub.PluginRequest(headers=headers, **kw)
    stub.set_request(module, req)
    return req


def call(suffix: str):
    """像 AstrBot 那样，从注册的路由取到 handler 并调用。"""
    return asyncio.run(context.handler_for(suffix)())


def upload(name: str = "meal.jpg", data: bytes = b"\xff\xd8\xff\xe0fake-jpeg-bytes"):
    return stub.PluginUploadFile(name, data)


ANALYZED: dict = {}


def patch_analyzer(result: dict | None = None, text_out: str | None = None):
    """把模型调用换成桩，并记录收到的参数。"""
    payload = result if result is not None else {
        "is_food": True, "title": "扬州炒饭", "meal": "午餐",
        "calories_kcal": 720, "protein_g": 31.5, "carbs_g": 81, "fat_g": 28.2,
        "items": [{"name": "米饭", "portion": "250g", "calories_kcal": 330}],
        "advice": "碳水偏高", "confidence": 0.82,
    }

    async def fake(path, prompt):
        ANALYZED["path"] = path
        ANALYZED["prompt"] = prompt
        if text_out is not None:
            return text_out, "stub"
        return json.dumps(payload, ensure_ascii=False), "stub:openai"

    plugin._analyze_openai = fake


# ================================================================ 1. 桩保真度
section("1. 测试桩保真度（防止再次掩盖此类 bug）")
check(
    "PluginMultiDict 不是 dict 子类（与上游一致）",
    isinstance(stub.PluginMultiDict([("a", 1)]), dict) is False,
)
check("PluginMultiDict.get 可用", stub.PluginMultiDict([("k", "v")]).get("k") == "v")
check("PluginMultiDict 缺失键返回 None", stub.PluginMultiDict([]).get("k") is None)
check("PluginMultiDict 支持同名多值", stub.PluginMultiDict([("k", 1), ("k", 2)]).getlist("k") == [1, 2])
check("PluginMultiDict 取最后一个值", stub.PluginMultiDict([("k", 1), ("k", 2)]).get("k") == 2)
check("PluginMultiDict 布尔值语义", bool(stub.PluginMultiDict([])) is False
      and bool(stub.PluginMultiDict([("a", 1)])) is True)

PROBE = stub.PluginRequest(files={"file": upload()}, form={"note": "x"}, query={"date": "2026-01-01"})
check("files() 返回 PluginMultiDict", isinstance(asyncio.run(PROBE.files()), stub.PluginMultiDict))
check("form() 返回 PluginMultiDict", isinstance(asyncio.run(PROBE.form()), stub.PluginMultiDict))
check("query 是 PluginMultiDict", isinstance(PROBE.query, stub.PluginMultiDict))

# ================================================================ 2. _pick
section("2. _pick 取值助手（本次线上问题的根因）")
pick = module._pick
check("dict 取值", pick({"a": 1}, "a") == 1)
check("PluginMultiDict 取值", pick(stub.PluginMultiDict([("a", 2)]), "a") == 2)
check("None 返回默认值", pick(None, "a", "d") == "d")
check("缺失键返回默认值", pick({}, "a", "d") == "d")
check("没有 get 方法的对象安全降级", pick(object(), "a", "d") == "d")
check("get 抛异常时安全降级", pick(type("X", (), {"get": lambda s, k, d=None: 1 / 0})(), "a", "d") == "d")
check("只接受单参 get 的对象也能用",
      pick(type("Y", (), {"get": lambda s, k: {"a": 9}.get(k)})(), "a", "d") == 9)

# ================================================================ 3. 解析
section("3. 模型输出 JSON 解析")
FENCE = chr(96) * 3
check("解析代码块包裹的 JSON",
      module.DietPlugin._parse_json(FENCE + 'json\n{"title":"A","calories_kcal":1}\n' + FENCE).get("title") == "A")
check("解析纯 JSON", module.DietPlugin._parse_json('{"is_food":false,"reason":"键盘"}').get("is_food") is False)
check("从混杂文本中抠 JSON",
      module.DietPlugin._parse_json('前缀 {"title":"面条"} 后缀').get("title") == "面条")
check("垃圾输出降级不抛异常",
      isinstance(module.DietPlugin._parse_json("模型罢工了"), dict))
check("空输出不抛异常", isinstance(module.DietPlugin._parse_json(""), dict))
check("JSON 数组降级为可展示结构",
      "解析失败" in str(module.DietPlugin._parse_json("[1,2,3]").get("title")))

# ================================================================ 4. 记录与汇总
section("4. 记录构造与营养汇总")
NOW = dt.datetime(2026, 6, 27, 12, 30)
REC = plugin._build_record(NOW, "2026-06-27", "120000_ab.jpg",
                           {"is_food": True, "title": "炒饭", "meal": "午餐",
                            "calories_kcal": "420.4", "protein_g": 35, "carbs_g": "20",
                            "fat_g": 12.5, "items": [], "advice": "ok", "_engine": "e"})
check("数值字段被安全转换", REC["calories_kcal"] == 420.4 and REC["protein_g"] == 35.0)
check("时间与日期字段正确", REC["date"] == "2026-06-27" and REC["time"] == "12:30")
check("引擎标记保留", REC["engine"] == "e")
check("非法数值降级为 0",
      plugin._build_record(NOW, "d", "x.jpg", {"calories_kcal": "很多"})["calories_kcal"] == 0.0)
NF = plugin._build_record(NOW, "2026-06-27", "y.jpg", {"is_food": False, "reason": "键盘"})
check("非食物记录被标记", NF["is_food"] is False and NF["title"] == "键盘")

plugin._append_record("2026-06-27", REC)
plugin._append_record("2026-06-27", NF)
loaded = plugin._load_records("2026-06-27")
check("jsonl 写入后可读回", len(loaded) == 2)
TOTALS = plugin._totals(loaded)
check("汇总跳过非食物记录", TOTALS["calories_kcal"] == 420.4)
check("营养素汇总正确", TOTALS["protein_g"] == 35.0 and TOTALS["fat_g"] == 12.5)

# ================================================================ 5. 鉴权
section("5. HMAC 上传鉴权")
GOOD = stub.auth_headers(plugin)["X-Diet-Token"]


def authorized(token: str | None) -> bool:
    stub.set_request(module, stub.PluginRequest(headers=({"X-Diet-Token": token} if token else {})))
    return plugin._authorized()


check("合法 token 通过", authorized(GOOD) is True)
check("篡改签名被拒绝", authorized(GOOD.split(".")[0] + "." + "0" * 64) is False)
PAST = str(int(time.time()) - 10)
check("过期 token 被拒绝", authorized(PAST + "." + plugin._sign(int(PAST))) is False)
check("缺失 token 被拒绝", authorized(None) is False)
check("格式错误被拒绝", authorized("garbage") is False)
check("空签名被拒绝", authorized("9999999999.") is False)
plugin.config["hmac_secret"] = "other"
check("换密钥后旧 token 失效", authorized(GOOD) is False)
plugin.config["hmac_secret"] = "unit-test-secret"
check("换回密钥后旧 token 恢复有效", authorized(GOOD) is True)

# ================================================================ 6. 路径穿越
section("6. 路径穿越防护")
check("正常文件名通过", str(plugin._safe_photo("2026-06-27", "a.jpg")).endswith("a.jpg"))
check("拒绝 ../", plugin._safe_photo("2026-06-27", "../../x.txt") is None)
check("拒绝绝对路径", plugin._safe_photo("2026-06-27", "C:\\Windows\\win.ini") is None)
check("拒绝非法日期", plugin._safe_photo("../../etc", "a.jpg") is None)
check("拒绝子目录", plugin._safe_photo("2026-06-27", "sub/a.jpg") is None)
check("拒绝空文件名", plugin._safe_photo("2026-06-27", "") is None)

# ================================================================ 7. 日报
section("7. 餐次推断与日报渲染")


def meal_at(h): return module.DietPlugin._guess_meal(dt.datetime(2026, 6, 27, h, 0))


check("08:00 早餐", meal_at(8) == "早餐")
check("12:00 午餐", meal_at(12) == "午餐")
check("19:00 晚餐", meal_at(19) == "晚餐")
check("15:00 加餐", meal_at(15) == "加餐")
check("02:00 夜宵", meal_at(2) == "夜宵")
REPORT = plugin._render_day("2026-06-27")
check("日报含标题与日期", "饮食日报" in REPORT and "2026-06-27" in REPORT)
check("日报含热量合计", "420" in REPORT)
check("日报含营养素行", "蛋白" in REPORT and "碳水" in REPORT)
check("日报含建议", "ok" in REPORT)
check("无记录时友好提示", "还没有饮食记录" in plugin._render_day("2000-01-01"))
check("区间汇总含日均", "日均" in plugin._render_range(["2026-06-26", "2026-06-27"], "最近两天"))

# ================================================================ 8. 配置容错
section("8. 配置容错")
plugin.config["extra_body"] = "{坏的"
check("非法 JSON 配置被忽略", plugin._json_conf("extra_body") == {})
plugin.config["extra_body"] = '{"top_p":0.9}'
check("合法 JSON 配置被解析", plugin._json_conf("extra_body") == {"top_p": 0.9})
plugin.config.pop("extra_body", None)
plugin.config["extra_headers"] = {"X-A": "1"}
check("字典形式的配置直接可用", plugin._json_conf("extra_headers") == {"X-A": "1"})
plugin.config.pop("extra_headers", None)

# ================================================================ 9. 端到端：文件上传（回归）
section("9. 端到端 · 照片上传（线上 bug 的回归测试）")
check("路由已注册", len(context.routes()) == 6, context.routes())
patch_analyzer()
DAY = dt.datetime.now(plugin.tz).strftime("%Y-%m-%d")
resp = call("/health")
check("GET /health 返回 200", resp.status_code == 200 and resp.payload.get("ok") is True)

make_request(files={"file": upload("chao_fan.jpg")})
resp = call("/analyze")
check("上传照片返回 200（曾经返回『缺少文件字段 file』）", resp.status_code == 200, resp.payload)
check("返回体包含 record", isinstance(resp.payload, dict) and "record" in resp.payload, resp.payload)
body = resp.payload.get("record", {})
check("识别结果被写入记录", body.get("title") == "扬州炒饭")
check("照片名已归档", bool(body.get("photo")) and str(body["photo"]).endswith(".jpg"))
check("模型确实收到了图片路径", ANALYZED.get("path") is not None, ANALYZED)
saved = plugin.photo_dir / DAY / str(body.get("photo"))
check("照片真的落盘了", saved.is_file() and saved.stat().st_size > 0, saved)
check("记录真的写进 jsonl 了", len(plugin._load_records(DAY)) >= 1)

# 字段名不同也应接受
ANALYZED.clear()
make_request(files={"image": upload("renamed.jpg")})
resp = call("/analyze")
check("字段名不是 file 时也能接受", resp.status_code == 200, resp.payload)

# 备注必须进入提示词（此前 note 被静默丢弃）
ANALYZED.clear()
make_request(files={"file": upload()}, form={"note": "这是我一个人吃的"})
resp = call("/analyze")
check("带备注上传返回 200", resp.status_code == 200, resp.payload)
check("备注被送进提示词", "一个人吃的" in str(ANALYZED.get("prompt", "")), ANALYZED.get("prompt"))
check("备注被存进记录", "一个人吃的" in str(resp.payload["record"].get("note")))

# 异常输入
make_request(files={})
resp = call("/analyze")
check("没有文件时返回『缺少文件字段 file』",
      resp.status_code == 400 and "缺少文件字段" in resp.payload["message"], resp.payload)

make_request(files={"file": upload("empty.jpg", b"")})
resp = call("/analyze")
check("空文件被拒绝", resp.status_code == 400 and "上传内容为空" in resp.payload["message"], resp.payload)

make_request(files={"file": upload("huge.jpg", b"\x00" * (module.MAX_UPLOAD_BYTES + 1))})
resp = call("/analyze")
check("超大文件被拒绝", resp.status_code == 400 and "过大" in resp.payload["message"], resp.payload)

# 鉴权
make_request(files={"file": upload()}, headers={"X-Diet-Token": "1." + "0" * 64})
resp = call("/analyze")
check("伪造签名上传被拒绝", resp.status_code == 400 and resp.payload["message"] == "unauthorized")

# ================================================================ 10. 纯文字
section("10. 端到端 · 纯文字记录")
ANALYZED.clear()
patch_analyzer(result={"is_food": True, "title": "牛肉面", "meal": "午餐",
                       "calories_kcal": 620, "protein_g": 28, "carbs_g": 78, "fat_g": 18,
                       "items": [], "advice": "汤别喝完"})
make_request(payload={"text": "中午吃了一碗牛肉面"})
resp = call("/analyze_text")
check("文字记录返回 200", resp.status_code == 200, resp.payload)
prec = resp.payload.get("record", {})
check("文字记录标题正确", prec.get("title") == "牛肉面")
check("文字模式不传图片", ANALYZED.get("path") is None)
check("文字模式用文字提示词", "文字描述" in str(ANALYZED.get("prompt", "")))
check("用户描述进入提示词", "牛肉面" in str(ANALYZED.get("prompt", "")))
check("文字记录 photo 为空", prec.get("photo") == "")
check("文字记录 source 标记", prec.get("source") == "app-text")
check("文字记录保留原文", prec.get("note") == "中午吃了一碗牛肉面")

make_request(payload={"text": ""})
resp = call("/analyze_text")
check("空文字被拒绝", resp.status_code == 400 and "缺少 text" in resp.payload["message"])

make_request(payload={"text": "x" * (module.MAX_TEXT_CHARS + 1)})
resp = call("/analyze_text")
check("超长文字被拒绝", resp.status_code == 400 and "过长" in resp.payload["message"])

make_request(payload=["不是对象"])
resp = call("/analyze_text")
check("非对象请求体被安全处理", resp.status_code == 400, resp.payload)

# ================================================================ 11. base64 归档
section("11. 端到端 · base64 补传归档")
import base64 as b64
make_request(payload={"content_base64": b64.b64encode(b"\xff\xd8fake").decode(),
                      "filename": "retry.jpg", "analyze": False})
resp = call("/archive")
check("仅归档返回 200", resp.status_code == 200 and resp.payload.get("saved"), resp.payload)
archived = plugin.photo_dir / str(resp.payload.get("date")) / str(resp.payload.get("saved"))
check("归档文件已落盘", archived.is_file(), archived)

make_request(payload={"content_base64": ""})
resp = call("/archive")
check("缺少 base64 被拒绝", resp.status_code == 400 and "content_base64" in resp.payload["message"])

make_request(payload={"content_base64": b64.b64encode(b"x" * (module.MAX_UPLOAD_BYTES + 1)).decode()})
resp = call("/archive")
check("base64 超大被拒绝", resp.status_code == 400 and "过大" in resp.payload["message"])

# ================================================================ 12. 查询与取图
section("12. 端到端 · 记录查询与照片取回")
make_request(query={"date": DAY})
resp = call("/records")
check("查询当天记录返回 200", resp.status_code == 200, resp.payload)
check("返回条数大于 0", resp.payload.get("count", 0) > 0, resp.payload)
check("返回总计字段", "totals" in resp.payload and "calories_kcal" in resp.payload["totals"])

make_request(query={"date": "不是日期"})
resp = call("/records")
check("非法日期被拒绝", resp.status_code == 400 and "YYYY-MM-DD" in resp.payload["message"])

make_request(query={})
resp = call("/records")
check("不传日期默认今天", resp.status_code == 200 and resp.payload.get("date") == DAY)

photo_name = str(prec.get("photo") or body.get("photo"))
make_request(query={"date": DAY, "name": photo_name})
resp = call("/photos" if False else "/photo")
check("取回照片返回 200", resp.status_code == 200, resp.payload)

make_request(query={"date": DAY, "name": "../../../main.py"})
resp = call("/photo")
check("取图接口拒绝路径穿越", resp.status_code == 400, resp.payload)

make_request(query={"date": DAY, "name": "不存在的文件.jpg"})
resp = call("/photo")
check("取不存在的图返回 400", resp.status_code == 400)

make_request(query={"date": DAY})
resp = call("/photo")
check("缺少文件名返回 400", resp.status_code == 400)

# 所有读写接口都必须鉴权
section("13. 所有接口都强制鉴权")
for suffix in ["/analyze", "/analyze_text", "/archive", "/records", "/photo"]:
    stub.set_request(module, stub.PluginRequest(headers={}, query={"date": DAY}, payload={}, files={}))
    resp = asyncio.run(context.handler_for(suffix)())
    check("%s 无凭据被拒绝" % suffix, resp.status_code == 400 and resp.payload["message"] == "unauthorized")

# ================================================================ 14. 日报标记
section("14. 日报区分照片与文字记录")
report = plugin._render_day(DAY)
check("照片记录用相机图标", "📷" in report, report[:200])
check("文字记录用书写图标", "✍️" in report, report[:200])
check("日报包含合计行", "合计" in report)

# ================================================================ 结果
print()
print("=" * 56)
print("通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    sys.exit(1)
print("全部通过 OK")
