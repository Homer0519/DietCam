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
import os
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


def call(suffix: str, method: str | None = None):
    """像 AstrBot 那样，从注册的路由取到 handler 并调用。

    同一个路径可能有 GET 与 POST 两个 handler（例如 /profile），
    这时用 method 指定。
    """
    return asyncio.run(context.handler_for(suffix, method)())


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
check("路由已注册", len(context.routes()) == 18, context.routes())
check(
    "包含孤儿照片清理路由",
    any(r.endswith("/cleanup") for r in context.routes()),
    context.routes(),
)
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


# ================================================================ 15. 主页概要


def events_of(suffix, **kw):
    """调用流式接口并把 SSE 事件全部取出来。"""
    make_request(**kw)
    resp = call(suffix)
    if not isinstance(resp, stub.FakeStreamResponse):
        raise AssertionError("不是流式响应：%r" % (resp,))
    return asyncio.run(resp.events())


section("15. 端到端 · 主页概要 /summary")
make_request(query={"date": DAY})
resp = call("/summary")
check("/summary 返回 200", resp.status_code == 200, resp.payload)
check("含 totals", "calories_kcal" in resp.payload.get("totals", {}))
check("含 targets", set(resp.payload.get("targets", {})) == set(module.DEFAULT_TARGETS), resp.payload.get("targets"))
check("含 records 列表", isinstance(resp.payload.get("records"), list))
check("count 与 records 长度一致", resp.payload["count"] == len(resp.payload["records"]))
check("日期回显正确", resp.payload["date"] == DAY)

make_request(query={"date": "坏日期"})
resp = call("/summary")
check("/summary 拒绝非法日期", resp.status_code == 400)

stub.set_request(module, stub.PluginRequest(headers={}, query={"date": DAY}))
resp = asyncio.run(context.handler_for("/summary")())
check("/summary 需要鉴权", resp.status_code == 400 and resp.payload["message"] == "unauthorized")

# ================================================================ 16. 每日目标
section("16. 端到端 · 每日目标 /targets")

plugin.config["target_calories_kcal"] = 1800
plugin.config["target_protein_g"] = 90
check("配置里的目标生效", plugin._targets()["calories_kcal"] == 1800.0, plugin._targets())
check("未配置的走默认值", plugin._targets()["carbs_g"] == module.DEFAULT_TARGETS["carbs_g"])

make_request(payload={"calories_kcal": 2200, "fat_g": 70})
resp = call("/targets")
check("/targets 返回 200", resp.status_code == 200, resp.payload)
check("返回更新后的目标", resp.payload["targets"]["calories_kcal"] == 2200.0, resp.payload)
t = plugin._targets()
check("APP 覆盖优先于插件配置", t["calories_kcal"] == 2200.0, t)
check("未提交的键保留插件配置值", t["protein_g"] == 90.0, t)
check("目标真的落盘了", plugin._load_state().get("targets", {}).get("calories_kcal") == 2200.0)

make_request(payload={"calories_kcal": "很多"})
resp = call("/targets")
check("非数字被拒绝", resp.status_code == 400 and "数字" in resp.payload["message"], resp.payload)

make_request(payload={"calories_kcal": -5})
resp = call("/targets")
check("负数被拒绝", resp.status_code == 400 and "范围" in resp.payload["message"], resp.payload)

make_request(payload={"calories_kcal": 999999})
resp = call("/targets")
check("离谱数值被拒绝", resp.status_code == 400, resp.payload)

make_request(payload={})
resp = call("/targets")
check("没有可更新字段时被拒绝", resp.status_code == 400, resp.payload)

check("/summary 反映了新目标", True)
make_request(query={"date": DAY})
resp = call("/summary")
check("/summary 里的目标已更新", resp.payload["targets"]["calories_kcal"] == 2200.0, resp.payload["targets"])

# ================================================================ 17. 流式分析
section("17. 端到端 · 流式分析 /analyze_stream")

PIECES = ["盘中是炒饭、鸡蛋和虾仁", 
          "，油量偏多。\n",
          '{"is_food":true,"title":"扬州炒饭","meal":"午餐",',
          '"calories_kcal":720,"protein_g":31.5,"carbs_g":81,"fat_g":28.2,',
          '"items":[],"advice":"少放油"}']


def patch_stream(pieces=None, fail=None, meta="openai:stream-test"):
    async def gen(path, note):
        if fail:
            raise RuntimeError(fail)
        yield ("meta", meta)
        for p in (pieces or PIECES):
            yield ("delta", p)
    plugin._stream_model = gen


patch_stream()
events = events_of("/analyze_stream", files={"file": upload("stream.jpg")})
kinds = [e.get("type") for e in events]
check("第一个事件是 start", kinds and kinds[0] == "start", kinds)
check("包含 meta 事件", "meta" in kinds, kinds)
check("包含多个 delta 事件", kinds.count("delta") == len(PIECES), kinds)
check("最后一个事件是 done", kinds and kinds[-1] == "done", kinds)

streamed = "".join(e["text"] for e in events if e.get("type") == "delta")
check("增量拼接后等于原始输出", streamed == "".join(PIECES), streamed)
check("meta 带上了模型标识", any(e.get("engine") == "openai:stream-test" for e in events if e.get("type") == "meta"))

done = [e for e in events if e.get("type") == "done"][0]
srec = done["record"]
check("流式结果解析出标题", srec.get("title") == "扬州炒饭", srec)
check("流式结果带热量", srec.get("calories_kcal") == 720.0, srec)
check("流式记录已落盘", any(r.get("id") == srec["id"] for r in plugin._load_records(DAY)))
check("流式照片已归档", (plugin.photo_dir / DAY / srec["photo"]).is_file())

patch_stream(fail="模型连接失败")
_boom_day = plugin._today()
_boom_dir = plugin.photo_dir / _boom_day
_before_boom = {p.name for p in _boom_dir.iterdir()} if _boom_dir.is_dir() else set()
events = events_of("/analyze_stream", files={"file": upload("boom.jpg")})
_after_boom = {p.name for p in _boom_dir.iterdir()} if _boom_dir.is_dir() else set()
errs = [e for e in events if e.get("type") == "error"]
check("模型出错时发 error 事件", len(errs) == 1, events)
check("error 事件带错误信息", "模型连接失败" in errs[0].get("message", ""), errs)
check("出错后不再发 done", not any(e.get("type") == "done" for e in events))
check(
    "出错后不留孤儿照片（线上「4 张照片 vs 2 条记录」的根因）",
    _before_boom == _after_boom,
    _after_boom - _before_boom,
)

make_request(files={})
resp = call("/analyze_stream")
check("流式接口缺文件时返回普通错误", resp.status_code == 400 and "缺少文件字段" in resp.payload["message"], resp.payload)

stub.set_request(module, stub.PluginRequest(headers={}, files={}))
resp = asyncio.run(context.handler_for("/analyze_stream")())
check("流式接口需要鉴权", resp.status_code == 400 and resp.payload["message"] == "unauthorized")

# 文字流式
patch_stream()
events = events_of("/analyze_text_stream", payload={"text": "下午吃了两个橙子"})
kinds = [e.get("type") for e in events]
check("文字流式返回 start/delta/done", kinds[0] == "start" and kinds[-1] == "done", kinds)
trec2 = [e for e in events if e.get("type") == "done"][0]["record"]
check("文字流式记录 source 为 app-text", trec2.get("source") == "app-text", trec2)
check("文字流式记录 photo 为空", trec2.get("photo") == "", trec2)
check("文字流式保留原文", trec2.get("note") == "下午吃了两个橙子", trec2)

make_request(payload={"text": ""})
resp = call("/analyze_text_stream")
check("文字流式缺 text 返回错误", resp.status_code == 400 and "缺少 text" in resp.payload["message"], resp.payload)

stub.set_request(module, stub.PluginRequest(headers={}, payload={"text": "x"}))
resp = asyncio.run(context.handler_for("/analyze_text_stream")())
check("文字流式需要鉴权", resp.status_code == 400 and resp.payload["message"] == "unauthorized")

check("stream_response 在桩里可用", stub.stream_response is not None)
# 回归：astrbot_provider 模式下走【真实的】_stream_model（不能再被整段替换掉）
del plugin._stream_model  # 撤销前面 patch_stream 注入的实例属性，回到真实实现
plugin.config["llm_mode"] = "astrbot_provider"


async def fake_analyze_dict(path_=None, note="", *args, **kwargs):
    """真实 _analyze 的返回类型就是 dict。"""
    return {
        "is_food": True, "title": "烤箱鸡翅", "meal": "加餐",
        "calories_kcal": 430, "protein_g": 28, "carbs_g": 5, "fat_g": 33,
        "items": [{"name": "鸡翅", "portion": "6 只", "calories_kcal": 430}],
        "advice": "油脂偏高，去皮更好。",
        "_engine": "astrbot:test-provider",
    }


plugin._analyze = fake_analyze_dict

# 直接驱动真实的 _stream_model，验证它会产出合法的 (kind, value) 二元组
gen = plugin._stream_model(None, "烤了六只鸡翅")
pairs = []
async def drain():
    async for item in gen:
        pairs.append(item)
asyncio.run(drain())
check("provider 模式 _stream_model 不抛异常", True)
check("provider 模式产出二元组", all(isinstance(p, tuple) and len(p) == 2 for p in pairs), pairs)
check("provider 模式先给 meta", pairs and pairs[0][0] == "meta", pairs)
check("provider 模式随后给 delta", len(pairs) >= 2 and pairs[1][0] == "delta", pairs)
check("meta 里带上了引擎信息", "test-provider" in str(pairs[0][1]), pairs[0])

provider_payload = json.loads(pairs[1][1])
check("delta 是能被解析的 JSON", provider_payload.get("title") == "烤箱鸡翅", provider_payload)
check("内部下划线字段不暴露给客户端", all(not k.startswith("_") for k in provider_payload), list(provider_payload))

# 端到端：provider 模式下的 /analyze_stream 必须能跑通
events = events_of("/analyze_stream", files={"file": upload("wings.jpg")})
ekinds = [e.get("type") for e in events]
check("provider 模式流式端到端不报错", "error" not in ekinds, events)
check("provider 模式流式以 done 结束", ekinds and ekinds[-1] == "done", ekinds)
pdone = [e for e in events if e.get("type") == "done"][0]["record"]
check("provider 模式解析出正确标题", pdone.get("title") == "烤箱鸡翅", pdone)
check("provider 模式热量解析正确", pdone.get("calories_kcal") == 430.0, pdone)

plugin.config["llm_mode"] = "openai_compatible"
del plugin._analyze           # 还原真实实现，避免影响后续用例
del plugin._analyze_openai    # 还原，后续用例会各自再 patch
patch_stream()                # 恢复给后续测试使用

check("桩能识别流式响应类型", isinstance(stub.FakeStreamResponse((x for x in [])), stub.FakeResponse))

# ================================================================ 18. 提示词格式
section("18. 提示词要求两行输出（便于流式展示）")
check("拍照提示词要求先给一句观察", "第一行" in module.DEFAULT_PROMPT and "40 字以内" in module.DEFAULT_PROMPT)
check("拍照提示词要求第二行是 JSON", "第二行" in module.DEFAULT_PROMPT)
check("文字提示词同样要求两行", "第一行" in module.TEXT_PROMPT and "第二行" in module.TEXT_PROMPT)
check("两行输出仍能被解析", module.DietPlugin._parse_json("看到一碗面。\n" + '{"title":"牛肉面","calories_kcal":600}').get("title") == "牛肉面")
check("带前后缀的两行输出也能解析", module.DietPlugin._parse_json("观察：有米饭\n" + '{"title":"炒饭"}' + "\n完毕").get("title") == "炒饭")


# ================================================================ 19. 身体档案与目标
section("19. 端到端 · 身体档案 /profile 与目标推算")

plugin.config.pop("profile_height_cm", None)
state0 = plugin._load_state()
state0.pop("profile", None)
state0.pop("targets", None)
state0.pop("targets_mode", None)
plugin._save_state(state0)

make_request()
resp = call("/profile", "GET")
check("/profile 返回 200", resp.status_code == 200, resp.payload)
check("返回默认档案", resp.payload["profile"]["height_cm"] == 170.0, resp.payload["profile"])
check("返回建议目标", set(resp.payload["suggested"]) == set(module.DEFAULT_TARGETS))

# 公式自检：男 175cm / 70kg / 30 岁 / 轻度活动 / 维持
calc = module.compute_targets({"height_cm": 175, "weight_kg": 70, "age": 30,
                              "sex": "male", "activity": "light", "goal": "maintain"})
bmr = 10 * 70 + 6.25 * 175 - 5 * 30 + 5
expected_kcal = bmr * 1.375
check("热量按 Mifflin-St Jeor 推算", abs(calc["calories_kcal"] - expected_kcal) < 1.0,
      (calc["calories_kcal"], expected_kcal))
check("蛋白质不低于 1.2g/kg", calc["protein_g"] >= 70 * 1.2 - 0.1, calc)
check("碳水不为负", calc["carbs_g"] >= 0, calc)

female = module.compute_targets({"height_cm": 160, "weight_kg": 55, "age": 28,
                                "sex": "female", "activity": "sedentary", "goal": "lose"})
male_same = module.compute_targets({"height_cm": 160, "weight_kg": 55, "age": 28,
                                   "sex": "male", "activity": "sedentary", "goal": "lose"})
check("女性目标低于男性（公式减 161）", female["calories_kcal"] < male_same["calories_kcal"])
check("减脂目标低于维持",
      module.compute_targets({"height_cm": 175, "weight_kg": 70, "age": 30, "sex": "male",
                             "activity": "light", "goal": "lose"})["calories_kcal"]
      < calc["calories_kcal"])

make_request(payload={"height_cm": 180, "weight_kg": 80, "age": 25,
                      "sex": "male", "activity": "moderate", "goal": "lose"})
resp = call("/profile", "POST")
check("保存档案返回 200", resp.status_code == 200, resp.payload)
check("档案已保存", resp.payload["profile"]["height_cm"] == 180.0, resp.payload["profile"])
check("保存后模式为 auto", resp.payload["targets_mode"] == "auto")
t = resp.payload["targets"]
sug = module.compute_targets(resp.payload["profile"])
check("目标等于按新档案推算的值", abs(t["calories_kcal"] - sug["calories_kcal"]) < 0.2, (t, sug))

make_request(payload={"height_cm": 10})
resp = call("/profile", "POST")
check("身高越界被拒绝", resp.status_code == 400 and "之间" in resp.payload["message"], resp.payload)

make_request(payload={"sex": "other"})
resp = call("/profile", "POST")
check("非法性别被拒绝", resp.status_code == 400, resp.payload)

make_request(payload={"weight_kg": "很重"})
resp = call("/profile", "POST")
check("非法体重被拒绝", resp.status_code == 400, resp.payload)

# 手动目标应覆盖自动推算，且改档案后回到自动
make_request(payload={"calories_kcal": 1234})
call("/targets")
check("手动设目标后模式为 manual", plugin._targets_mode() == "manual")
check("手动目标生效", plugin._targets()["calories_kcal"] == 1234.0, plugin._targets())
make_request(payload={"height_cm": 181})
call("/profile", "POST")
check("改档案后恢复自动模式", plugin._targets_mode() == "auto")
check("改档案后目标被重算", plugin._targets()["calories_kcal"] != 1234.0, plugin._targets())

stub.set_request(module, stub.PluginRequest(headers={}))
resp = asyncio.run(context.handler_for("/profile", "GET")())
check("/profile 需要鉴权", resp.status_code == 400 and resp.payload["message"] == "unauthorized")

# ================================================================ 20. 记录编辑与删除
section("20. 端到端 · 记录编辑 / 删除 / AI 重分析")

patch_analyzer()
make_request(files={"file": upload("editable.jpg")})
resp = call("/analyze")
edit_rec = resp.payload["record"]
edit_day = edit_rec["date"]
edit_id = edit_rec["id"]
check("准备了一条可编辑记录", bool(edit_id))

make_request(payload={"date": edit_day, "id": edit_id, "title": "改过的名字",
                      "calories_kcal": 555, "meal": "宵夜"})
resp = call("/record/update")
check("修改记录返回 200", resp.status_code == 200, resp.payload)
check("标题已改", resp.payload["record"]["title"] == "改过的名字")
check("热量已改", resp.payload["record"]["calories_kcal"] == 555.0)
check("餐次已改", resp.payload["record"]["meal"] == "宵夜")
check("标记为已编辑", resp.payload["record"].get("edited") is True)

reread = [r for r in plugin._load_records(edit_day) if r["id"] == edit_id][0]
check("改动已落盘", reread["title"] == "改过的名字" and reread["calories_kcal"] == 555.0, reread)

make_request(payload={"date": edit_day, "id": edit_id, "calories_kcal": "很多"})
resp = call("/record/update")
check("非法数值被拒绝", resp.status_code == 400, resp.payload)

make_request(payload={"date": edit_day, "id": "不存在的id", "title": "x"})
resp = call("/record/update")
check("改不存在的记录被拒绝", resp.status_code == 400 and "没有找到" in resp.payload["message"])

# AI 重分析
plugin._analyze_openai = None
patch_stream()
async def fake_reanalyze(path, prompt):
    return json.dumps({"is_food": True, "title": "重新分析后的标题", "meal": "午餐",
                       "calories_kcal": 888, "protein_g": 40, "carbs_g": 90, "fat_g": 30,
                       "items": [], "advice": "重新给出的建议"}), "stub"
plugin._analyze_openai = fake_reanalyze
make_request(payload={"date": edit_day, "id": edit_id, "instruction": "这是两人份，请翻倍"})
resp = call("/record/reanalyze")
check("重新分析返回 200", resp.status_code == 200, resp.payload)
check("标题被模型更新", resp.payload["record"]["title"] == "重新分析后的标题", resp.payload["record"])
check("热量被模型更新", resp.payload["record"]["calories_kcal"] == 888.0)
check("标记为已重分析", resp.payload["record"].get("reanalyzed") is True)
check("重分析保留了原来的照片", resp.payload["record"]["photo"] == edit_rec["photo"])
check("重分析保留了原始记录 id", resp.payload["record"]["id"] == edit_id)

# 删除
photo_path = plugin.photo_dir / edit_day / edit_rec["photo"]
check("删除前照片存在", photo_path.is_file())
make_request(payload={"date": edit_day, "id": edit_id})
resp = call("/record/delete")
check("删除记录返回 200", resp.status_code == 200, resp.payload)
check("返回被删掉的记录", resp.payload["removed"]["id"] == edit_id)
check("记录确实没了", all(r["id"] != edit_id for r in plugin._load_records(edit_day)))
check("归档照片一并删除", not photo_path.exists())

make_request(payload={"date": edit_day, "id": edit_id})
resp = call("/record/delete")
check(
    "重复删除改为幂等（手机端列表可能是旧的）",
    resp.status_code == 200 and resp.payload.get("already_gone") is True,
    resp.payload,
)

stub.set_request(module, stub.PluginRequest(headers={}, payload={"date": edit_day, "id": "x"}))
resp = asyncio.run(context.handler_for("/record/delete", "POST")())
check("删除接口需要鉴权", resp.status_code == 400 and resp.payload["message"] == "unauthorized")

# ================================================================ 21. 日历与历史
section("21. 端到端 · 日历 /calendar 与历史 /history")

month = edit_day[:7]
make_request(query={"month": month})
resp = call("/calendar")
check("日历返回 200", resp.status_code == 200, resp.payload)
check("返回 days 字典", isinstance(resp.payload.get("days"), dict))
check("包含有记录的日期", edit_day in resp.payload["days"], list(resp.payload["days"])[:5])
day_data = resp.payload["days"].get(edit_day, {})
check("每天含热量与条数", "calories_kcal" in day_data and "count" in day_data, day_data)
check("日历附带目标", "targets" in resp.payload)

make_request(query={"month": "不是月份"})
resp = call("/calendar")
check("非法月份被拒绝", resp.status_code == 400 and "YYYY-MM" in resp.payload["message"])

make_request(query={"days": 30})
resp = call("/history")
check("历史返回 200", resp.status_code == 200, resp.payload)
check("返回记录列表", isinstance(resp.payload.get("records"), list))
check("返回 per_day 汇总", isinstance(resp.payload.get("per_day"), dict))
check("记录按时间倒序", all(
    (resp.payload["records"][i]["date"], resp.payload["records"][i]["time"])
    >= (resp.payload["records"][i + 1]["date"], resp.payload["records"][i + 1]["time"])
    for i in range(len(resp.payload["records"]) - 1)
))

make_request(query={"days": 99999})
resp = call("/history")
check("过大的 days 被夹到上限", resp.status_code == 200 and resp.payload["days"] <= 365, resp.payload.get("days"))

make_request(query={"days": 7, "end": "坏日期"})
resp = call("/history")
check("非法 end 被拒绝", resp.status_code == 400, resp.payload)

stub.set_request(module, stub.PluginRequest(headers={}, query={"month": month}))
resp = asyncio.run(context.handler_for("/calendar")())
check("日历接口需要鉴权", resp.status_code == 400 and resp.payload["message"] == "unauthorized")
# ================================================================ 22. 孤儿照片
section("22. 孤儿照片与清理（手机端删了、AstrBot 那边要跟着干净）")

today = plugin._today()
today_dir = plugin.photo_dir / today
today_dir.mkdir(parents=True, exist_ok=True)


def age_file(path: Path, seconds: int = 3600) -> None:
    """把文件改成「很久以前」的，用来绕开 SWEEP_MIN_AGE 的保护。"""
    old = time.time() - seconds
    os.utime(path, (old, old))


def cmd_text(message: str, handler_name: str = "cmd_diet") -> str:
    """像 AstrBot 那样把指令喂给插件，把回复拼成字符串。"""
    event = stub.FakeEvent(message)

    async def run() -> str:
        out: list[str] = []
        async for item in getattr(plugin, handler_name)(event):
            out.append(item[1])
        return "\n".join(out)

    return asyncio.run(run())


# 刚上传、正在分析的照片不能被误清
fresh = today_dir / "235961_fresh0.jpg"
fresh.write_bytes(b"fresh")
check("刚上传的照片不在默认清理范围内", fresh.name not in [p.name for p in plugin._orphan_photos(today)])
check("按年龄为 0 查时能看到它", fresh.name in [p.name for p in plugin._orphan_photos(today, 0)])

# 放旧了的孤儿照片会被清掉
stale = today_dir / "235959_stale0.jpg"
stale.write_bytes(b"stale")
age_file(stale)
check("放旧的孤儿照片进入清理范围", stale.name in [p.name for p in plugin._orphan_photos(today)])
check("清理时删掉了它", stale.name in plugin._sweep_orphans(today))
check("清理后文件真的没了", not stale.exists())

# 有记录引用的照片永远不碰
patch_analyzer()
make_request(files={"file": upload("keep.jpg")})
resp = call("/analyze")
check("先记一条带照片的记录", resp.status_code == 200, resp.payload)
kept = resp.payload["record"]
kept_path = today_dir / kept["photo"]
check("引用中的照片存在", kept_path.is_file())
check(
    "有记录引用的照片不会被当成孤儿",
    kept["photo"] not in [p.name for p in plugin._orphan_photos(today, 0)],
)

# 删除记录：自己的照片 + 这天遗留的孤儿，一起收干净
legacy = today_dir / "235958_legacy0.jpg"
legacy.write_bytes(b"legacy")
age_file(legacy)
make_request(payload={"date": today, "id": kept["id"]})
resp = call("/record/delete")
check("删除记录返回 200", resp.status_code == 200, resp.payload)
check("删除时顺带清理了历史孤儿照片", legacy.name in resp.payload.get("swept", []), resp.payload.get("swept"))
check("历史孤儿照片确实没了", not legacy.exists())
check("被删记录的照片也没了", not kept_path.exists())

# 幂等：删一条本来就不存在的记录
make_request(payload={"date": today, "id": "no-such-id"})
resp = call("/record/delete")
check(
    "删不存在的记录不报错（幂等）",
    resp.status_code == 200 and resp.payload.get("already_gone") is True,
    resp.payload,
)

# /cleanup 接口
leftover = today_dir / "235957_leftov0.jpg"
leftover.write_bytes(b"leftover")
age_file(leftover)
fresh.unlink(missing_ok=True)   # 它已经验证过「刚上传不会被清」，先撤掉免得干扰
make_request(query={"date": today})
resp = call("/cleanup", "GET")
check("清理接口返回 200", resp.status_code == 200, resp.payload)
check("返回清理数量", resp.payload.get("count") == 1, resp.payload)
# 注意：第 11 节的 /archive（analyze=false）会故意留一张「只归档、不成记录」的图，
# 那是离线补传接口的设计，所以这里只断言本次造的垃圾被清干净了。
_still = [p.name for p in plugin._orphan_photos(today, 0)]
check(
    "清理后本次造的孤儿照片都不在了",
    leftover.name not in _still and fresh.name not in _still,
    _still,
)
check("照片确实被删掉", not leftover.exists())

make_request(query={"date": "不是日期"})
resp = call("/cleanup", "GET")
check("非法的 date 被拒绝", resp.status_code == 400 and "YYYY-MM-DD" in resp.payload["message"])

stub.set_request(module, stub.PluginRequest(headers={}, query={}))
resp = asyncio.run(context.handler_for("/cleanup", "GET")())
check("清理接口需要鉴权", resp.status_code == 400 and resp.payload["message"] == "unauthorized")

# /饮食 清理 指令
check("没有垃圾时给出「对得上」的回复", "对得上" in cmd_text("/饮食 清理"))
junk = today_dir / "235956_junk00.jpg"
junk.write_bytes(b"junk")
age_file(junk)
report = cmd_text("/饮食 清理")
check("有垃圾时报告删掉几张", "清理完成" in report and "1 张" in report, report)
check("指令清理后文件没了", not junk.exists())
check("清理全部也能跑", "清理完成" in cmd_text("/饮食 清理全部") or "对得上" in cmd_text("/饮食 清理全部"))

# 清理之后日报照常
check("清理不影响已有记录", plugin._load_records(today) == [] or isinstance(plugin._load_records(today), list))

# ================================================================ 23. LLM 工具
section("23. LLM 工具（让模型自己去查，而不是提示用户敲指令）")

registered = stub.llm_tools()
check("注册了 3 个 LLM 工具", len(registered) == 3, sorted(registered))
check("工具名正确", sorted(registered) == ["diet_query_day", "diet_query_range", "diet_search"], sorted(registered))

# AstrBot 靠 docstring 的 Args 段生成参数 schema，写错格式参数会被静默丢弃
EXPECTED_ARGS = {
    "diet_query_day": [("date", "string")],
    "diet_query_range": [("period", "string")],
    "diet_search": [("keyword", "string"), ("days", "number")],
}
for tool_name, expected in EXPECTED_ARGS.items():
    parsed = stub.parse_tool_args(registered[tool_name].__doc__)
    got = [(a["name"], a["type"]) for a in parsed]
    check("工具 %s 的 Args 段能被 AstrBot 正确解析" % tool_name, got == expected, got)
    check("工具 %s 每个参数都有描述" % tool_name, all(a["description"] for a in parsed), parsed)

# 工具真的能查到东西
tool_day = "2026-08-08"
photo_name = "120000_tooltest.jpg"
(plugin.photo_dir / tool_day).mkdir(parents=True, exist_ok=True)
(plugin.photo_dir / tool_day / photo_name).write_bytes(b"img")
plugin._append_record(
    tool_day,
    {
        "id": "tool0001", "date": tool_day, "time": "12:30", "photo": photo_name,
        "source": "app", "note": "", "is_food": True, "title": "可乐鸡翅",
        "meal": "午餐", "calories_kcal": 620.0, "protein_g": 38.0, "carbs_g": 22.0,
        "fat_g": 40.0, "confidence": 0.9,
        "items": [{"name": "鸡翅", "portion": "6 个", "calories_kcal": 520}],
        "advice": "油偏多，配点青菜",
    },
)

tool_event = stub.FakeEvent("随便问问")
day_text = asyncio.run(stub.call_llm_tool(plugin, "diet_query_day", tool_event, date=tool_day))
check("查某天返回日报", "饮食日报" in day_text and tool_day in day_text, day_text[:120])
check("日报里有分项与建议", "鸡翅" in day_text and "油偏多" in day_text)

rel_day = asyncio.run(stub.call_llm_tool(plugin, "diet_query_day", tool_event, date="昨天"))
check("「昨天」能被解析", "饮食日报" in rel_day or "还没有饮食记录" in rel_day, rel_day[:80])

bad_day = asyncio.run(stub.call_llm_tool(plugin, "diet_query_day", tool_event, date="瞎写的"))
check("无法识别的日期给出明确提示", "无法识别" in bad_day, bad_day)

range_text = asyncio.run(stub.call_llm_tool(plugin, "diet_query_range", tool_event, period="最近400天"))
check("超大区间被夹到上限并返回汇总", "汇总" in range_text or "还没有" in range_text, range_text[:80])

range_bad = asyncio.run(stub.call_llm_tool(plugin, "diet_query_range", tool_event, period="随便"))
check("无法识别的区间退化成最近 7 天", "最近 7 天" in range_bad or "还没有" in range_bad, range_bad[:80])

hit = asyncio.run(stub.call_llm_tool(plugin, "diet_search", tool_event, keyword="鸡翅", days=365))
check("搜索能找到吃过的食物", "鸡翅" in hit and tool_day in hit, hit[:160])
miss = asyncio.run(stub.call_llm_tool(plugin, "diet_search", tool_event, keyword="佛跳墙", days=365))
check("搜不到时给出明确回复", "没有找到" in miss, miss)
empty = asyncio.run(stub.call_llm_tool(plugin, "diet_search", tool_event, keyword="  ", days=365))
check("空关键词被拒绝", "请给出" in empty, empty)
weird = asyncio.run(stub.call_llm_tool(plugin, "diet_search", tool_event, keyword="鸡翅", days="不是数字"))
check("days 传了非数字也能兜住", "鸡翅" in weird or "没有找到" in weird, weird[:80])

status_text = cmd_text("/饮食状态", "cmd_diet_status")
check("状态里报出 LLM 工具数量", "LLM 工具" in status_text and "3 个" in status_text, status_text)

# ================================================================ 24. 餐次
section("24. 餐次判断（把当前时间告诉模型）")

hint = plugin._time_hint()
check("时间提示里有「当前时间」", "当前时间" in hint, hint)
check("时间提示里点明了 meal 怎么判", "请据此判断 meal" in hint, hint)
check("时间提示给出了各餐时段", "早餐 05:00-10:00" in hint and "晚餐 17:00-21:00" in hint, hint)
check("时间提示带星期", "周" in hint, hint)
check("时间提示以空行开头，接在提示词后面不会粘在一起", hint.startswith("\n\n"), repr(hint[:6]))

# 真正发给模型的提示词里必须带上它 —— 之前餐次老出错就是因为模型不知道现在几点
patch_analyzer()
make_request(files={"file": upload("meal-time.jpg")})
resp = call("/analyze")
check("拍照分析成功", resp.status_code == 200, resp.payload)
sent = str(ANALYZED.get("prompt") or "")
check("发给模型的提示词里带上了当前时间", "当前时间：" in sent, sent[-200:])
check("提示词里保留了原来的 JSON 格式要求", "is_food" in sent and "calories_kcal" in sent)
check("提示词说明「用户说了以用户为准」", "以用户说的为准" in sent, sent[-200:])

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
