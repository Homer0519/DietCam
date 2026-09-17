# -*- coding: utf-8 -*-
"""DietCam 插件端到端演示。

在忠实的 AstrBot 模拟环境里（tools/astrbot_stub.py，PluginMultiDict 不是 dict），
像真实客户端那样从注册的路由调用插件接口，完整走一遍：

    1. GET  /health        健康检查
    2. POST /analyze       上传一张炒饭照片（会真的写进磁盘）
    2b.POST /analyze_text  纯文字记录（不拍照）
    3. GET  /records       查询当天记录
    4. GET  /photo         取回归档照片
    5. 安全校验：伪造签名 / 无签名 / 路径穿越
    6. 聊天里的 /饮食 输出
    6b.注册给模型的 LLM 工具（模型自己去查记录）
    6c.孤儿照片清理
    7. 磁盘文件结构

视觉模型用桩替代，不需要任何模型服务即可运行。
"""
from __future__ import annotations

import asyncio
import datetime as dt
import json
import os
import shutil
import struct
import sys
import time
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import astrbot_stub as stub  # noqa: E402

ROOT = HERE.parent
DEMO_DIR = ROOT / ".demo_data"
PHOTO_IN = DEMO_DIR / "chao_fan.png"


# ---------------------------------------------------------------- 造一张图

def make_png(width: int, height: int) -> bytes:
    """纯 Python 合成一张「一盘炒饭」的 PNG，不需要 Pillow。"""
    rows = []
    cx, cy, r = width / 2, height / 2, min(width, height) * 0.40
    for y in range(height):
        row = bytearray()
        for x in range(width):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy < r * r:
                seed = (x * 7919 + y * 104729) % 1000
                if seed < 40:
                    px = (86, 150, 60)       # 青豆
                elif seed < 75:
                    px = (226, 140, 46)      # 胡萝卜
                elif seed < 110:
                    px = (250, 214, 96)      # 鸡蛋
                else:
                    px = (236, 205, 138)     # 米饭
            elif dx * dx + dy * dy < (r + 10) ** 2:
                px = (238, 238, 240)         # 盘沿
            else:
                px = (58, 46, 36)            # 桌面
            row += bytes(px)
        rows.append(b"\x00" + bytes(row))
    raw = b"".join(rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


# ------------------------------------------------------------ 模型桩数据

FRIED_RICE = {
    "is_food": True,
    "title": "扬州炒饭配紫菜蛋花汤",
    "meal": "午餐",
    "items": [
        {"name": "米饭", "portion": "约 250g", "calories_kcal": 330, "protein_g": 6.5, "carbs_g": 72.0, "fat_g": 1.0},
        {"name": "鸡蛋", "portion": "1 个", "calories_kcal": 70, "protein_g": 6.0, "carbs_g": 0.5, "fat_g": 5.0},
        {"name": "虾仁", "portion": "约 40g", "calories_kcal": 45, "protein_g": 9.0, "carbs_g": 0.5, "fat_g": 0.5},
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

TYPED_MEAL = {
    "is_food": True,
    "title": "无糖豆浆配茶叶蛋",
    "meal": "加餐",
    "items": [
        {"name": "无糖豆浆", "portion": "1 杯 300ml", "calories_kcal": 90, "protein_g": 9.0, "carbs_g": 4.5, "fat_g": 3.5},
        {"name": "茶叶蛋", "portion": "1 个", "calories_kcal": 78, "protein_g": 6.5, "carbs_g": 1.0, "fat_g": 5.0},
    ],
    "calories_kcal": 168,
    "protein_g": 15.5,
    "carbs_g": 5.5,
    "fat_g": 8.5,
    "confidence": 0.65,
    "advice": "描述里没有主食，如果这是正餐建议补一点碳水；当作加餐则很合适。",
}


async def main() -> int:
    if DEMO_DIR.exists():
        shutil.rmtree(DEMO_DIR)
    DEMO_DIR.mkdir(parents=True, exist_ok=True)

    logger, _ctx = stub.install(DEMO_DIR)
    module, plugin, context = stub.load_plugin(
        {
            "llm_mode": "openai_compatible",
            "base_url": "http://127.0.0.1:8000/v1",
            "model": "qwen2.5-vl-7b-instruct",
            "hmac_secret": "demo-secret",
            "timezone_offset_hours": 8,
            "analyze_prompt": "",
        },
        module_name="diet_demo",
    )

    STREAM_MEAL = {
        "is_food": True,
        "title": "番茄牛腩饭",
        "meal": "晚餐",
        "items": [
            {"name": "米饭", "portion": "约 200g", "calories_kcal": 260, "protein_g": 5.0, "carbs_g": 58.0, "fat_g": 0.8},
            {"name": "番茄牛腩", "portion": "约 250g", "calories_kcal": 420, "protein_g": 32.0, "carbs_g": 12.0, "fat_g": 26.0},
        ],
        "calories_kcal": 680,
        "protein_g": 37.0,
        "carbs_g": 70.0,
        "fat_g": 26.8,
        "confidence": 0.79,
        "advice": "牛腩脂肪偏高，配一份清炒青菜会更平衡。",
    }
    queue = [FRIED_RICE, SNACK, TYPED_MEAL, STREAM_MEAL]

    def next_item(prompt: str) -> dict:
        note = ""
        if "补充说明：" in prompt:
            note = prompt.split("补充说明：", 1)[1].strip()
        item = dict(queue.pop(0))
        if note:
            item["advice"] = item["advice"] + "（已参考你的说明：%s）" % note
        return item

    async def fake_analyze(path, prompt):
        await asyncio.sleep(0)
        return json.dumps(next_item(prompt), ensure_ascii=False), "openai:qwen2.5-vl-7b-instruct (stub)"

    plugin._analyze_openai = fake_analyze

    async def fake_stream(path, note):
        """模拟真实的流式输出：先来一句观察，再逐段吐 JSON。"""
        item = next_item(note)
        observation = "盘中是%s，整体%s。" % (
            item["title"],
            "油量偏多" if item["fat_g"] > 20 else "比较清淡",
        )
        await asyncio.sleep(0)
        yield ("meta", "openai:qwen2.5-vl-7b-instruct (stub)")
        for ch in observation:
            yield ("delta", ch)
            await asyncio.sleep(0)
        yield ("delta", "\n")
        blob = json.dumps(item, ensure_ascii=False)
        step = max(1, len(blob) // 6)
        for i in range(0, len(blob), step):
            yield ("delta", blob[i:i + step])
            await asyncio.sleep(0)

    plugin._stream_model = fake_stream

    def headers():
        return dict(stub.auth_headers(plugin))

    def set_request(**kw):
        h = headers()
        h.update(kw.pop("headers", {}) or {})
        stub.set_request(module, stub.PluginRequest(headers=h, **kw))

    async def call(suffix):
        return await context.handler_for(suffix)()

    line = "=" * 66
    print(line)
    print(" DietCam 插件演示 —— 在忠实模拟的 AstrBot 环境里端到端跑一遍")
    print(line)
    print()
    print("插件启动时注册的 Web API：")
    for route, handler, methods, desc in context.registered_web_apis:
        print("    %-4s %-46s %s" % (",".join(methods), route, desc))

    # 1. health
    print()
    print("[1] GET /health   健康检查")
    set_request()
    resp = await call("/health")
    print("    HTTP %d" % resp.status_code)
    print("    " + json.dumps(resp.payload, ensure_ascii=False))

    # 2. 照片
    print()
    print("[2] POST /analyze   上传一张炒饭照片")
    png = make_png(320, 320)
    PHOTO_IN.write_bytes(png)
    print("    生成测试图片 chao_fan.png（%d 字节，纯 Python 合成）" % len(png))
    set_request(
        files={"file": stub.PluginUploadFile("chao_fan.png", png, "image/png")},
        form={"note": "午饭，外卖"},
    )
    t0 = dt.datetime.now()
    resp = await call("/analyze")
    cost = (dt.datetime.now() - t0).total_seconds()
    print("    HTTP %d   耗时 %.3fs" % (resp.status_code, cost))
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
    print("      建议      %s" % rec["advice"])
    for it in rec["items"]:
        print("        · %-12s %-10s %4d kcal" % (it["name"], it.get("portion", ""), it["calories_kcal"]))
    print("      备注      %s" % rec.get("note", ""))

    set_request(files={"file": stub.PluginUploadFile("snack.png", make_png(240, 240), "image/png")})
    await call("/analyze")

    # 2b. 文字
    print()
    print("[2b] POST /analyze_text   不拍照，直接打字描述")
    typed = "下午喝了一杯无糖豆浆和一个茶叶蛋"
    print("    输入：%s" % typed)
    set_request(payload={"text": typed})
    resp = await call("/analyze_text")
    print("    HTTP %d" % resp.status_code)
    trec = resp.payload["record"]
    print("      标题      %s" % trec["title"])
    print("      热量      %g kcal（蛋白 %g / 碳水 %g / 脂肪 %g）"
          % (trec["calories_kcal"], trec["protein_g"], trec["carbs_g"], trec["fat_g"]))
    print("      建议      %s" % trec["advice"])
    print("      归档字段  photo=%r  source=%r" % (trec["photo"], trec["source"]))

    # ------------------------------------------------ 2c. 流式分析（SSE）
    print()
    print("[2c] POST /analyze_stream   流式分析（服务端逐段下发）")
    set_request(files={"file": stub.PluginUploadFile("stream.png", make_png(280, 280), "image/png")})
    resp = await call("/analyze_stream")
    print("    HTTP %d   Content-Type: %s" % (resp.status_code, resp.content_type))
    events = await resp.events()
    deltas = [e for e in events if e.get("type") == "delta"]
    print("    收到 %d 个 SSE 事件（其中 delta %d 个）：" % (len(events), len(deltas)))
    streamed = ""
    for ev in events:
        kind = ev.get("type")
        if kind == "start":
            print("      · start        开始")
        elif kind == "meta":
            print("      · meta         engine=%s" % ev.get("engine"))
        elif kind == "delta":
            streamed += ev.get("text", "")
        elif kind == "done":
            rs = ev["record"]
            print("      · done         %s · %g kcal" % (rs["title"], rs["calories_kcal"]))
        elif kind == "error":
            print("      · error        %s" % ev.get("message"))
    print()
    print("    把 delta 按到达顺序拼起来，就是模型的原始输出：")
    for ln in streamed.splitlines():
        print("      | " + ln[:96])

    # --------------------------------------------------- 2d. 主页概要
    print()
    print("[2d] GET /summary   主页需要的当日概览")
    set_request(query={})
    resp = await call("/summary")
    payload = resp.payload
    print("    HTTP %d" % resp.status_code)
    print("    日期   %s" % payload["date"])
    print("    合计   %g kcal｜蛋白 %g｜碳水 %g｜脂肪 %g" % (
        payload["totals"]["calories_kcal"], payload["totals"]["protein_g"],
        payload["totals"]["carbs_g"], payload["totals"]["fat_g"]))
    print("    目标   %g kcal｜蛋白 %g｜碳水 %g｜脂肪 %g" % (
        payload["targets"]["calories_kcal"], payload["targets"]["protein_g"],
        payload["targets"]["carbs_g"], payload["targets"]["fat_g"]))
    used = payload["totals"]["calories_kcal"] / payload["targets"]["calories_kcal"] * 100
    print("    进度   热量已用 %.0f%%" % used)

    # --------------------------------------------------- 2e. 调整目标
    print()
    print("[2e] POST /targets   调整每日目标（存 state.json，不动插件配置）")
    set_request(payload={"calories_kcal": 1900, "protein_g": 100})
    resp = await call("/targets")
    print("    HTTP %d   更新后：%s" % (
        resp.status_code, json.dumps(resp.payload["targets"], ensure_ascii=False)))

    # 3. records
    day = dt.datetime.now(plugin.tz).strftime("%Y-%m-%d")
    print()
    print("[3] GET /records?date=%s   查询当天记录" % day)
    set_request(query={"date": day})
    resp = await call("/records")
    print("    HTTP %d" % resp.status_code)
    print("    条数 %d" % resp.payload["count"])
    print("    合计 " + json.dumps(resp.payload["totals"], ensure_ascii=False))

    # 4. photo
    print()
    print("[4] GET /photo   取回归档照片")
    set_request(query={"date": day, "name": rec["photo"]})
    resp = await call("/photo")
    p = Path(resp.payload)
    print("    HTTP %d  ->  %s (%d 字节)" % (resp.status_code, p.name, p.stat().st_size if p.exists() else -1))

    # 5. 安全
    print()
    print("[5] 安全校验")
    set_request(files={"file": stub.PluginUploadFile("x.png", png)},
                headers={"X-Diet-Token": "9999999999." + "0" * 64})
    resp = await call("/analyze")
    print("    伪造签名 -> HTTP %d  %s" % (resp.status_code, resp.payload["message"]))

    stub.set_request(module, stub.PluginRequest(headers={}, query={"date": day}))
    resp = await context.handler_for("/records")()
    print("    无签名   -> HTTP %d  %s" % (resp.status_code, resp.payload["message"]))

    set_request(query={"date": day, "name": "../../../main.py"})
    resp = await call("/photo")
    print("    路径穿越 -> HTTP %d  %s" % (resp.status_code, resp.payload["message"]))

    stub.set_request(module, stub.PluginRequest(headers={}, files={}))
    resp = await context.handler_for("/analyze_stream")()
    print("    流式无签名 -> HTTP %d  %s" % (resp.status_code, resp.payload["message"]))

    # 6. 指令
    class Ev(stub.FakeEvent):
        pass

    async def run_cmd(text):
        out = []
        async for item in plugin.cmd_diet(Ev(text)):
            out.append(item[1])
        return "\n".join(out)

    for cmd in ["/饮食", "/饮食 本周"]:
        print()
        print("[6] 聊天里发送 %s   输出：" % cmd)
        print("    " + "-" * 58)
        for ln in (await run_cmd(cmd)).splitlines():
            print("    " + ln)
        print("    " + "-" * 58)

    # 6b. LLM 工具：模型自己去查，不用敲指令
    print()
    print("[6b] 注册给模型的 LLM 工具（直接问「我这周蛋白质够吗」即可）")
    for tool_name, fn in sorted(stub.llm_tools().items()):
        args = stub.parse_tool_args(fn.__doc__)
        print("    %-18s 参数：%s" % (
            tool_name,
            "、".join("%s(%s)" % (a["name"], a["type"]) for a in args) or "无",
        ))
    print()
    print("    模型问一句「我这周吃过鸡蛋吗」，工具返回：")
    print("    " + "-" * 58)
    hit = await stub.call_llm_tool(plugin, "diet_search", Ev("查鸡蛋"), keyword="蛋", days=30)
    for ln in hit.splitlines():
        print("    " + ln)
    print("    " + "-" * 58)

    # 6c. 孤儿照片清理
    print()
    print("[6c] 孤儿照片清理（照片比记录多的时候）")
    orphan_day = plugin._today()
    orphan_dir = plugin.photo_dir / orphan_day
    orphan_dir.mkdir(parents=True, exist_ok=True)
    orphan = orphan_dir / "000000_orphan0.png"
    orphan.write_bytes(b"x")
    old = time.time() - 3600
    os.utime(orphan, (old, old))
    print("    造一张没人认领的 %s -> 孤儿列表：%s" % (
        orphan.name, [p.name for p in plugin._orphan_photos(orphan_day)]))
    print("    发送 /饮食 清理  ->")
    for ln in (await run_cmd("/饮食 清理")).splitlines():
        print("      " + ln)
    print("    清理后孤儿列表：%s" % [p.name for p in plugin._orphan_photos(orphan_day)])

    # 7. 磁盘
    print()
    print("[7] 服务器上落盘的文件（这就是【不进聊天上下文】的那份数据）")
    for path in sorted(DEMO_DIR.rglob("*")):
        if path.is_file():
            print("    %-46s %7d B" % (path.relative_to(DEMO_DIR), path.stat().st_size))

    print()
    print("[8] 记录文件的一行原始内容（records/%s.jsonl）" % day)
    first = (plugin.data_dir / "records" / (day + ".jsonl")).read_text(encoding="utf-8").splitlines()[0]
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
