# -*- coding: utf-8 -*-
"""astrbot_plugin_dsh_bridge 的自测。

跑法：python tools/test_astrbot_plugin.py

测试跑在 tools/astrbot_stub.py 上——一个刻意复刻上游怪癖的 AstrBot 桩
（PluginMultiDict 不是 dict、路由必须带插件名前缀、send_message 找不到平台
返回 False 等），这样这里验过的行为在真 AstrBot 上才站得住。
"""

from __future__ import annotations

import asyncio
import base64
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import astrbot_stub as stub  # noqa: E402

# Node 侧独立算出的同一个 HMAC，用来锁死两端令牌算法一致
TOKEN_VECTOR = "1b74eaf87da46877ba651ac812b24a3bf01990e9ed0101b88268f60f965729e2"

SECRET = "test-secret"

_results: list[tuple[str, bool, str]] = []
_module_counter = 0


def check(name: str, condition: object, detail: str = "") -> bool:
    ok = bool(condition)
    _results.append((name, ok, detail))
    return ok


def eq(name: str, actual: object, expected: object) -> bool:
    return check(name, actual == expected, "实际=%r 期望=%r" % (actual, expected))


# 沙箱只保证工作区可写，所以测试数据目录放在项目里而不是系统临时目录
_TMP_ROOT = Path(__file__).resolve().parent / ".test_tmp"


async def call_api(context, suffix: str, method: str = "GET"):
    """按路由取 handler 并调用（注册的是绑定方法，取出来还要调用一次）。"""
    handler = context.handler_for(suffix, method)
    return await handler()


def make_plugin(config: dict | None = None):
    """每个用例一套干净的模块 + 插件实例。"""
    global _module_counter
    _module_counter += 1
    _TMP_ROOT.mkdir(parents=True, exist_ok=True)
    tmp = _TMP_ROOT / ("case-%d-%d" % (_module_counter, int(time.time() * 1000) % 1000000))
    tmp.mkdir(parents=True, exist_ok=True)
    stub.install(tmp)
    merged = {"hmac_secret": SECRET}
    merged.update(config or {})
    module, plugin, context = stub.load_plugin(merged, "dsh_bridge_test_%d" % _module_counter)
    return tmp, module, plugin, context


def auth(plugin, ttl: int = 300) -> dict:
    return stub.auth_headers(plugin, ttl)


def query_request(plugin, **query) -> stub.PluginRequest:
    return stub.PluginRequest(
        method="GET", headers=auth(plugin), query=query, plugin_name=stub.PLUGIN_NAME
    )


def json_request(plugin, payload: dict, method: str = "POST") -> stub.PluginRequest:
    return stub.PluginRequest(
        method=method, headers=auth(plugin), payload=payload, plugin_name=stub.PLUGIN_NAME
    )


def sample_event(**overrides) -> stub.FakeEvent:
    params = dict(
        message_str="帮我看看这个",
        umo="aiocqhttp:FriendMessage:10001",
        sender_id="10001",
        sender_name="小明",
        is_private=True,
    )
    params.update(overrides)
    return stub.FakeEvent(**params)


# ============================================================ 注册与鉴权


async def test_routes():
    _, _, plugin, context = make_plugin()
    routes = context.routes()
    eq("注册 5 个接口", len(routes), 5)
    for suffix in ("/health", "/inbox", "/reply", "/reset", "/whoami"):
        check("路由包含 %s" % suffix, any(r.endswith(suffix) for r in routes), str(routes))
    check(
        "所有路由都带插件名前缀",
        all(r.startswith("/" + stub.PLUGIN_NAME + "/") for r in routes),
        str(routes),
    )
    check("inbox 只接受 GET", context.handler_for("/inbox", "GET") is not None)
    try:
        context.handler_for("/inbox", "POST")
        check("inbox 不接受 POST", False)
    except KeyError:
        check("inbox 不接受 POST", True)
    check("health 只接受 GET", context.handler_for("/health", "GET") is not None)
    check("reply 只接受 POST", context.handler_for("/reply", "POST") is not None)


async def test_token_matches_node():
    _, _, plugin, _c = make_plugin()
    eq("令牌与 Node 实现逐字节一致", plugin._sign(1700000000), TOKEN_VECTOR)


async def test_auth():
    _, module, plugin, context = make_plugin()

    async def call(path, headers, suffix, method="GET", payload=None):
        stub.set_request(
            module,
            stub.PluginRequest(method=method, headers=headers, query={}, payload=payload,
                               plugin_name=stub.PLUGIN_NAME),
        )
        return await call_api(context, suffix, method)

    # 缺令牌
    resp = await call("/inbox", {}, "/inbox")
    eq("缺令牌 -> 401", resp.status_code, 401)
    # 非法格式
    resp = await call("/inbox", {"X-DSH-Bridge-Token": "abc"}, "/inbox")
    eq("令牌格式错误 -> 401", resp.status_code, 401)
    # 签名被篡改
    good = auth(plugin)["X-DSH-Bridge-Token"]
    exp, _sig = good.split(".", 1)
    resp = await call("/inbox", {"X-DSH-Bridge-Token": exp + ".deadbeef"}, "/inbox")
    eq("签名错误 -> 401", resp.status_code, 401)
    # 已过期
    expired = auth(plugin, ttl=-10)["X-DSH-Bridge-Token"]
    resp = await call("/inbox", {"X-DSH-Bridge-Token": expired}, "/inbox")
    eq("过期令牌 -> 401", resp.status_code, 401)
    # 换一把密钥签的令牌也不行
    other = plugin._sign(int(time.time()) + 300)
    plugin.config["hmac_secret"] = "another-secret"
    resp = await call("/inbox", {"X-DSH-Bridge-Token": "%d.%s" % (int(time.time()) + 300, other)}, "/inbox")
    eq("换密钥后旧签名失效 -> 401", resp.status_code, 401)
    plugin.config["hmac_secret"] = ""
    resp = await call("/inbox", {"X-DSH-Bridge-Token": good}, "/inbox")
    eq("未配置密钥时一律拒绝", resp.status_code, 401)

    # 正常令牌应通过
    plugin.config["hmac_secret"] = SECRET
    resp = await call("/inbox", auth(plugin), "/inbox")
    eq("合法令牌 -> 200", resp.status_code, 200)

    for suffix, method in (("/reply", "POST"), ("/reset", "POST"), ("/whoami", "GET")):
        resp = await call(suffix, {}, suffix, method, {} if method == "POST" else None)
        eq("%s 缺令牌 -> 401" % suffix, resp.status_code, 401)


# ============================================================ 入站收件


async def test_inbound_private():
    _, _, plugin, _c = make_plugin()
    event = sample_event()
    await plugin.on_any_message(event)
    eq("私聊消息入队", len(plugin._queue), 1)
    item = plugin._queue[0]
    eq("umo 正确", item["umo"], "aiocqhttp:FriendMessage:10001")
    eq("文本正确", item["text"], "帮我看看这个")
    eq("发送者 id", item["sender_id"], "10001")
    eq("发送者昵称", item["sender_name"], "小明")
    eq("is_private", item["is_private"], True)
    eq("mentioned", item["mentioned"], False)
    eq("kind", item["kind"], "message")
    check("带 cursor", item["cursor"] == 1)
    check("带 id", isinstance(item["id"], str) and item["id"])
    check("带时间戳", isinstance(item["ts"], int))
    eq("默认抑制默认 LLM 请求", event.call_llm, True)
    eq("默认不终止事件传播", event.stopped, False)


async def test_inbound_group_mention_rules():
    _, _, plugin, _c = make_plugin()

    plain = sample_event(
        message_str="大家早上好",
        umo="aiocqhttp:GroupMessage:555",
        group_id="555", is_private=False,
        components=[stub.Plain("大家早上好")],
    )
    await plugin.on_any_message(plain)
    eq("群里没 @ 不转发", len(plugin._queue), 0)

    at = sample_event(
        message_str="帮我查一下",
        umo="aiocqhttp:GroupMessage:555",
        group_id="555", is_private=False, self_id="99999",
        components=[stub.At("99999"), stub.Plain(" 帮我查一下")],
    )
    await plugin.on_any_message(at)
    eq("群里 @机器人 转发", len(plugin._queue), 1)
    eq("mentioned 为真", plugin._queue[0]["mentioned"], True)

    at_all = sample_event(
        message_str="通知",
        umo="aiocqhttp:GroupMessage:555",
        group_id="555", is_private=False,
        components=[stub.AtAll()],
    )
    await plugin.on_any_message(at_all)
    eq("@全体成员也算 @", len(plugin._queue), 2)

    other = sample_event(
        message_str="别人",
        umo="aiocqhttp:GroupMessage:555",
        group_id="555", is_private=False, self_id="99999",
        components=[stub.At("12345")],
    )
    await plugin.on_any_message(other)
    eq("只 @ 别人不转发", len(plugin._queue), 2)


async def test_forward_mode_all():
    _, _, plugin, _c = make_plugin({"forward_mode": "all"})
    event = sample_event(message_str="群聊闲聊", umo="aiocqhttp:GroupMessage:555",
                         group_id="555", is_private=False)
    await plugin.on_any_message(event)
    eq("forward_mode=all 时群里闲聊也转发", len(plugin._queue), 1)


async def test_disabled_and_empty():
    _, _, plugin, _c = make_plugin({"enabled": False})
    await plugin.on_any_message(sample_event())
    eq("enabled=false 时不收件", len(plugin._queue), 0)

    _, _, plugin2, _c2 = make_plugin()
    await plugin2.on_any_message(sample_event(message_str="   "))
    eq("空消息不入队", len(plugin2._queue), 0)


async def test_stop_event_option():
    _, _, plugin, _c = make_plugin({"stop_event": True})
    event = sample_event()
    await plugin.on_any_message(event)
    eq("stop_event=true 时终止传播", event.stopped, True)

    _, _, plugin2, _c2 = make_plugin({"suppress_default_llm": False})
    event2 = sample_event()
    await plugin2.on_any_message(event2)
    eq("关闭抑制时不碰 call_llm", event2.call_llm, False)


# ============================================================ 图片


async def test_inbound_images():
    tmp, _, plugin, _c = make_plugin()
    jpeg = tmp / "a.jpg"
    jpeg.write_bytes(b"\xff\xd8\xff\xe0" + b"x" * 200)
    png = tmp / "b.png"
    png.write_bytes(b"\x89PNG\r\n\x1a\n" + b"y" * 200)

    event = sample_event(
        components=[stub.Image(path=str(jpeg)), stub.Image(path=str(png)), stub.Plain("看看")],
    )
    await plugin.on_any_message(event)
    eq("图片入队", len(plugin._queue), 1)
    images = plugin._queue[0]["images"]
    eq("两张图片", len(images), 2)
    eq("JPEG 识别", images[0]["mediaType"], "image/jpeg")
    eq("PNG 识别", images[1]["mediaType"], "image/png")
    eq("base64 内容正确", base64.b64decode(images[0]["base64"])[:3], b"\xff\xd8\xff")
    eq("没有 note", plugin._queue[0]["notes"], [])


async def test_image_limits():
    tmp, _, plugin, _c = make_plugin({"max_image_bytes": 50, "max_images": 1})
    big = tmp / "big.jpg"
    big.write_bytes(b"\xff\xd8\xff" + b"z" * 500)
    small = tmp / "small.jpg"
    small.write_bytes(b"\xff\xd8\xff" + b"z" * 10)

    event = sample_event(components=[stub.Image(path=str(small)), stub.Image(path=str(big))])
    await plugin.on_any_message(event)
    images = plugin._queue[0]["images"]
    eq("只保留 1 张（max_images）", len(images), 1)
    check("保留的是小图", base64.b64decode(images[0]["base64"]).endswith(b"z" * 10))

    _, _, plugin2, _c2 = make_plugin({"max_image_bytes": 10})
    event2 = sample_event(components=[stub.Image(path=str(big))])
    await plugin2.on_any_message(event2)
    eq("超大图片全部丢弃", len(plugin2._queue[0]["images"]), 0)
    check("给出提示", len(plugin2._queue[0]["notes"]) == 1, str(plugin2._queue[0]["notes"]))

    # 读不到的图片走 base64 回退
    _, _, plugin3, _c3 = make_plugin()
    event3 = sample_event(components=[stub.Image(path=str(tmp / "missing.jpg"),
                                                 base64=base64.b64encode(b"\xff\xd8\xffok").decode())])
    await plugin3.on_any_message(event3)
    eq("读不到路径时回退 base64", len(plugin3._queue[0]["images"]), 1)

    _, _, plugin4, _c4 = make_plugin({"forward_images": False})
    event4 = sample_event(components=[stub.Image(path=str(small))])
    await plugin4.on_any_message(event4)
    eq("forward_images=false 时不带图", len(plugin4._queue[0]["images"]), 0)


# ============================================================ 取件


async def test_inbox_basic():
    _, module, plugin, context = make_plugin()
    for i in range(3):
        plugin._enqueue({"kind": "message", "umo": "u", "text": "msg%d" % i, "ts": int(time.time())})

    stub.set_request(module, query_request(plugin, cursor=0, timeout=0, limit=2))
    resp = await call_api(context, "/inbox", "GET")
    eq("首次取件 200", resp.status_code, 200)
    body = resp.payload
    eq("按 limit 返回 2 条", len(body["events"]), 2)
    eq("游标推进到第 2 条", body["cursor"], 2)
    eq("还有更多", body["more"], True)
    eq("head 是最大序号", body["head"], 3)

    stub.set_request(module, query_request(plugin, cursor=body["cursor"], timeout=0, limit=2))
    resp2 = await call_api(context, "/inbox", "GET")
    eq("续取拿到剩下 1 条", len(resp2.payload["events"]), 1)
    eq("more 为假", resp2.payload["more"], False)

    stub.set_request(module, query_request(plugin, cursor=resp2.payload["cursor"], timeout=0))
    resp3 = await call_api(context, "/inbox", "GET")
    eq("取空后返回 0 条", len(resp3.payload["events"]), 0)
    eq("游标不变", resp3.payload["cursor"], 3)
    eq("队列已裁剪", len(plugin._queue), 0)


async def test_inbox_long_poll_wakes_on_new_event():
    _, module, plugin, context = make_plugin()
    stub.set_request(module, query_request(plugin, cursor=0, timeout=3))
    task = asyncio.create_task(call_api(context, "/inbox", "GET"))
    await asyncio.sleep(0.1)
    plugin._enqueue({"kind": "message", "umo": "u", "text": "迟到的消息", "ts": int(time.time())})
    resp = await asyncio.wait_for(task, 3)
    eq("长轮询被新事件唤醒", len(resp.payload["events"]), 1)
    eq("内容正确", resp.payload["events"][0]["text"], "迟到的消息")


async def test_inbox_respects_timeout():
    _, module, plugin, context = make_plugin()
    stub.set_request(module, query_request(plugin, cursor=0, timeout=0.4))
    started = time.time()
    resp = await asyncio.wait_for(call_api(context, "/inbox", "GET"), 3)
    elapsed = time.time() - started
    eq("超时返回空", len(resp.payload["events"]), 0)
    check("确实等到了超时（秒级带小数也认）", elapsed >= 0.35, "elapsed=%.2f" % elapsed)

    # timeout 超过 max_poll_seconds 时会被夹住
    _, module2, plugin2, context2 = make_plugin({"max_poll_seconds": 0})
    stub.set_request(module2, query_request(plugin2, cursor=0, timeout=30))
    started = time.time()
    await asyncio.wait_for(call_api(context2, "/inbox", "GET"), 3)
    check("max_poll_seconds 生效", time.time() - started < 1.0)


async def test_inbox_bad_cursor():
    _, module, plugin, context = make_plugin()
    stub.set_request(module, query_request(plugin, cursor="abc", timeout=0))
    resp = await call_api(context, "/inbox", "GET")
    eq("非法 cursor 退回 0 而不是报错", resp.status_code, 200)


async def test_queue_overflow():
    _, _, plugin, _c = make_plugin({"max_queue": 2})
    for i in range(4):
        plugin._enqueue({"kind": "message", "umo": "u", "text": "m%d" % i, "ts": int(time.time())})
    eq("队列被限制在 max_queue", len(plugin._queue), 2)
    eq("丢弃计数", plugin._dropped, 2)
    eq("保留的是最新的", plugin._queue[-1]["text"], "m3")


# ============================================================ 回发


async def test_reply_basic():
    _, module, plugin, context = make_plugin()
    stub.set_request(module, json_request(plugin, {"umo": "aiocqhttp:FriendMessage:1", "text": "你好"}))
    resp = await call_api(context, "/reply", "POST")
    eq("回发 200", resp.status_code, 200)
    eq("回发成功", resp.payload["ok"], True)
    eq("发送 1 条", resp.payload["sent"], 1)
    eq("落到正确的会话", context.sent[0][0], "aiocqhttp:FriendMessage:1")
    eq("文本正确", context.sent[0][1].get_plain_text(), "你好")
    eq("发送计数", plugin._sent, 1)


async def test_reply_splits_long_text():
    _, module, plugin, context = make_plugin({"max_message_chars": 20})
    long_text = "".join("第%d句话，还挺长的。" % i for i in range(6))
    stub.set_request(module, json_request(plugin, {"umo": "u", "text": long_text}))
    resp = await call_api(context, "/reply", "POST")
    check("切成了多条", resp.payload["chunks"] > 1, str(resp.payload))
    eq("每条都发出去了", resp.payload["sent"], resp.payload["chunks"])
    eq("实际发送条数一致", len(context.sent), resp.payload["chunks"])
    for _umo, chain in context.sent:
        check("单条不超长", len(chain.get_plain_text()) <= 20, chain.get_plain_text())
    eq("内容没丢", "".join(c.get_plain_text() for _u, c in context.sent), long_text)
    eq("发送计数累加", plugin._sent, resp.payload["chunks"])


async def test_reply_validation_and_failure():
    _, module, plugin, context = make_plugin()

    stub.set_request(module, json_request(plugin, {"text": "没有 umo"}))
    eq("缺 umo -> 400", (await call_api(context, "/reply", "POST")).status_code, 400)

    stub.set_request(module, json_request(plugin, {"umo": "u", "text": "   "}))
    eq("空 text -> 400", (await call_api(context, "/reply", "POST")).status_code, 400)

    stub.set_request(module, json_request(plugin, {"umo": "u", "text": 123}))
    eq("非字符串 text -> 400", (await call_api(context, "/reply", "POST")).status_code, 400)

    context.send_should_fail = True
    stub.set_request(module, json_request(plugin, {"umo": "u", "text": "发不出去"}))
    resp = await call_api(context, "/reply", "POST")
    eq("平台不在线时 ok=false", resp.payload["ok"], False)
    eq("失败计数", plugin._send_failed, 1)


async def test_health_and_whoami():
    _, module, plugin, context = make_plugin()
    resp = await call_api(context, "/health", "GET")
    eq("health 200", resp.status_code, 200)
    eq("health 报插件名", resp.payload["plugin"], stub.PLUGIN_NAME)
    check("health 带队列信息", "cursor" in resp.payload and "queued" in resp.payload)
    check("health 带 features", "features" in resp.payload)

    # health 不做鉴权（用于探活），whoami 必须鉴权
    stub.set_request(module, stub.PluginRequest(method="GET", query={}, plugin_name=stub.PLUGIN_NAME))
    eq("health 不需要令牌", (await call_api(context, "/health", "GET")).status_code, 200)
    eq("whoami 需要令牌", (await call_api(context, "/whoami", "GET")).status_code, 401)


async def test_reset():
    _, module, plugin, context = make_plugin()
    for i in range(3):
        plugin._enqueue({"kind": "message", "umo": "u", "text": "m", "ts": int(time.time())})
    stub.set_request(module, json_request(plugin, {}))
    resp = await call_api(context, "/reset", "POST")
    eq("reset 清空队列", len(plugin._queue), 0)
    eq("reset 报告清掉的条数", resp.payload["cleared"], 3)
    eq("reset 里的 dropped 仍然是累计丢弃数", resp.payload["dropped"], 0)
    eq("游标不倒退", plugin._delivered, 3)


async def test_status_command():
    _, _, plugin, _c = make_plugin()
    await plugin.on_any_message(sample_event())
    event = sample_event()
    chunks = [item async for item in plugin.dsh_status(event)]
    eq("指令产出一条回复", len(chunks), 1)
    text = chunks[0][1]
    check("包含标题", "DSH 桥接状态" in text, text)
    check("包含密钥状态", "已设置" in text, text)
    check("包含待取条数", "待取事件：1" in text, text)

    _, _, plugin2, _c2 = make_plugin({"hmac_secret": ""})
    chunks2 = [item async for item in plugin2.dsh_status(sample_event())]
    check("未配置密钥时明确告警", "未设置" in chunks2[0][1], chunks2[0][1])


async def test_missing_secret_warns():
    _tmp, module, plugin, context = make_plugin({"hmac_secret": ""})
    check("构造时日志里提示未配置密钥", "hmac_secret" in context_logger_text(module), context_logger_text(module))
    stub.set_request(
        module,
        stub.PluginRequest(method="GET", headers={"X-DSH-Bridge-Token": "1.abc"},
                           query={}, plugin_name=stub.PLUGIN_NAME),
    )
    resp = await call_api(context, "/inbox", "GET")
    eq("空密钥时拒绝", resp.status_code, 401)


# ============================================================ 纯函数


def context_logger_text(module) -> str:
    logger = sys.modules["astrbot.api"].logger
    return logger.text()


async def test_split_text():
    from importlib import import_module

    module = import_module("dsh_bridge_test_%d" % _module_counter)
    split = module.split_text
    eq("短文本不切", split("你好", 100), ["你好"])
    eq("空文本", split("   ", 100), [])
    long_text = "第一段。\n\n第二段。\n\n第三段。"
    parts = split(long_text, 8)
    check("确实切开了", len(parts) > 1, str(parts))
    for part in parts:
        check("每段不超长", len(part) <= 8, part)
    eq("内容没丢", "".join(parts).replace("\n", ""), long_text.replace("\n", ""))
    hard = "x" * 30
    hard_parts = split(hard, 10)
    eq("无边界时硬切且不丢字", "".join(hard_parts), hard)
    eq("limit<=0 时不切", split("abc", 0), ["abc"])


async def test_guess_media_type():
    from importlib import import_module

    module = import_module("dsh_bridge_test_%d" % _module_counter)
    eq("JPEG 魔数", module._guess_media_type(None, b"\xff\xd8\xff\x00"), "image/jpeg")
    eq("PNG 魔数", module._guess_media_type(None, b"\x89PNG\r\n\x1a\n"), "image/png")
    eq("GIF 魔数", module._guess_media_type(None, b"GIF89a"), "image/gif")
    eq("WebP 魔数", module._guess_media_type(None, b"RIFF\x00\x00\x00\x00WEBP"), "image/webp")
    eq("靠后缀兜底", module._guess_media_type("a.png", b"unknown"), "image/png")
    eq("认不出就按 JPEG", module._guess_media_type(None, b"unknown"), "image/jpeg")


async def test_pick_duck_typing():
    from importlib import import_module

    module = import_module("dsh_bridge_test_%d" % _module_counter)
    md = stub.PluginMultiDict([("a", "1")])
    check("PluginMultiDict 不是 dict", not isinstance(md, dict))
    eq("_pick 能从非 dict 取值", module._pick(md, "a"), "1")
    eq("_pick 缺省值", module._pick(md, "missing", "d"), "d")
    eq("_pick 处理 None", module._pick(None, "a", "d"), "d")
    eq("_pick 处理裸 dict", module._pick({"a": "2"}, "a"), "2")


# ============================================================ 入口


async def main():
    tests = [
        test_routes,
        test_token_matches_node,
        test_auth,
        test_inbound_private,
        test_inbound_group_mention_rules,
        test_forward_mode_all,
        test_disabled_and_empty,
        test_stop_event_option,
        test_inbound_images,
        test_image_limits,
        test_inbox_basic,
        test_inbox_long_poll_wakes_on_new_event,
        test_inbox_respects_timeout,
        test_inbox_bad_cursor,
        test_queue_overflow,
        test_reply_basic,
        test_reply_splits_long_text,
        test_reply_validation_and_failure,
        test_health_and_whoami,
        test_reset,
        test_status_command,
        test_missing_secret_warns,
        test_split_text,
        test_guess_media_type,
        test_pick_duck_typing,
    ]

    for test in tests:
        tmp = None
        try:
            await test()
        except Exception as exc:  # noqa: BLE001
            import traceback

            check("!! %s 抛异常" % test.__name__, False, traceback.format_exc(limit=4))
            _ = exc

    shutil.rmtree(_TMP_ROOT, ignore_errors=True)

    passed = sum(1 for _n, ok, _d in _results if ok)
    failed = [(n, d) for n, ok, d in _results if not ok]

    for name, ok, detail in _results:
        if not ok:
            print("  FAIL  %s\n        %s" % (name, detail))
    print("\nastrbot_plugin_dsh_bridge: %d/%d 通过" % (passed, len(_results)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
