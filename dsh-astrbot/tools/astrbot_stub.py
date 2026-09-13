# -*- coding: utf-8 -*-
"""忠实模拟 AstrBot 运行环境的测试桩（DSH 桥接插件专用）。

刻意复刻的几个上游特性，都是会真出问题的地方：

  * PluginMultiDict **不是 dict 子类**；
  * register_web_api 的路由必须以 /<plugin_name>/ 开头；
  * send_message 找不到平台时返回 False 而不是抛错；
  * should_call_llm(True) 的语义是「禁止默认 LLM 请求」（名字看着像反过来）；
  * event_message_type 装饰器把 handler 收集起来，需要显式取出来调用。

对照的上游实现：astrbot/api/web.py、astrbot/core/star/context.py、
astrbot/core/platform/astr_message_event.py（v4.28 master）。
"""

from __future__ import annotations

import enum
import importlib.util
import sys
import types
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "astrbot_plugin_dsh_bridge"
PLUGIN_MAIN = PLUGIN_DIR / "main.py"
PLUGIN_NAME = "astrbot_plugin_dsh_bridge"


# ----------------------------------------------------------------- 请求侧


class PluginMultiDict:
    """对照 astrbot.api.web.PluginMultiDict。故意不继承 dict。"""

    def __init__(self, pairs: list[tuple[str, Any]] | None = None) -> None:
        self._pairs: list[tuple[str, Any]] = list(pairs or [])

    def get(self, key: str, default: Any = None, type: Any = None) -> Any:
        for item_key, item_value in reversed(self._pairs):
            if item_key != key:
                continue
            if type is None:
                return item_value
            try:
                return type(item_value)
            except (TypeError, ValueError):
                return default
        return default

    def getlist(self, key: str) -> list[Any]:
        return [v for k, v in self._pairs if k == key]

    def keys(self):
        return dict.fromkeys(k for k, _ in self._pairs).keys()

    def values(self) -> list[Any]:
        return [self[k] for k in self.keys()]

    def items(self) -> list[tuple[str, Any]]:
        return [(k, self[k]) for k in self.keys()]

    def __contains__(self, key: str) -> bool:
        return any(k == key for k, _ in self._pairs)

    def __getitem__(self, key: str) -> Any:
        value = self.get(key)
        if value is None and key not in self:
            raise KeyError(key)
        return value

    def __bool__(self) -> bool:
        return bool(self._pairs)

    def __len__(self) -> int:
        return len(self.keys())

    def __repr__(self) -> str:
        return "PluginMultiDict(%r)" % (self._pairs,)


class PluginRequest:
    """对照 astrbot.api.web.PluginRequest。"""

    def __init__(
        self,
        *,
        method: str = "POST",
        headers: dict[str, str] | None = None,
        query: dict[str, Any] | list[tuple[str, Any]] | None = None,
        payload: Any = None,
        plugin_name: str = PLUGIN_NAME,
    ) -> None:
        def to_pairs(value):
            if value is None:
                return []
            if isinstance(value, dict):
                return list(value.items())
            return list(value)

        self.method = method
        # 上游是 Starlette Headers，名字大小写不敏感
        self.headers = _CaseInsensitiveDict(headers or {})
        self.query = PluginMultiDict(to_pairs(query))
        self.path_params: dict[str, Any] = {}
        self.plugin_name = plugin_name
        self.client_host = "127.0.0.1"
        self.content_type = self.headers.get("content-type")
        self._payload = payload

    async def body(self) -> bytes:
        return b""

    async def json(self, default: Any = None) -> Any:
        return self._payload if self._payload is not None else default

    async def form(self) -> PluginMultiDict:
        return PluginMultiDict()

    async def files(self) -> PluginMultiDict:
        return PluginMultiDict()


class _CaseInsensitiveDict(dict):
    def get(self, key: str, default: Any = None) -> Any:  # type: ignore[override]
        for k, v in self.items():
            if str(k).lower() == str(key).lower():
                return v
        return default

    def __contains__(self, key: object) -> bool:
        return any(str(k).lower() == str(key).lower() for k in self.keys())


# ----------------------------------------------------------------- 响应侧


class FakeResponse:
    def __init__(self, kind: str, payload: Any, status_code: int = 200) -> None:
        self.kind = kind
        self.payload = payload
        self.status_code = status_code

    def __repr__(self) -> str:
        return "<FakeResponse %s %s %r>" % (self.status_code, self.kind, self.payload)


def json_response(data: Any = None, *, status_code: int = 200, **_: Any) -> FakeResponse:
    return FakeResponse("json", {} if data is None else data, status_code)


def error_response(message: str, *, status_code: int = 400, data: Any = None, **_: Any) -> FakeResponse:
    return FakeResponse("error", {"message": message, "data": data}, status_code)


def stream_response(content: Any, **_: Any) -> FakeResponse:
    return FakeResponse("stream", content)


def file_response(path: Any, **_: Any) -> FakeResponse:
    return FakeResponse("file", str(path))


# --------------------------------------------------------------- 消息组件


class Plain:
    def __init__(self, text: str = "") -> None:
        self.text = text

    def __repr__(self) -> str:
        return "Plain(%r)" % (self.text,)


class At:
    def __init__(self, qq: Any = "", name: str | None = None) -> None:
        self.qq = qq
        self.name = name


class AtAll:
    pass


class Image:
    """对照 astrbot.core.message.components.Image（只保留插件用到的部分）。"""

    def __init__(self, file: str | None = None, *, url: str | None = None,
                 path: str | None = None, base64: str | None = None) -> None:
        self.file = file
        self.url = url
        self.path = path
        self._base64 = base64

    async def convert_to_base64(self) -> str:
        if self._base64 is not None:
            return self._base64
        if self.path:
            import base64 as _b64

            return _b64.b64encode(Path(self.path).read_bytes()).decode("ascii")
        raise RuntimeError("没有可用的图片来源")


class MessageChain:
    def __init__(self, chain: list[Any] | None = None, **_: Any) -> None:
        self.chain = list(chain or [])

    def message(self, text: str) -> "MessageChain":
        self.chain.append(Plain(text))
        return self

    def get_plain_text(self, **_: Any) -> str:
        return "".join(getattr(c, "text", "") for c in self.chain if isinstance(c, Plain))


# ------------------------------------------------------------------ 宿主


class FakeLogger:
    def __init__(self) -> None:
        self.lines: list[tuple[str, str]] = []

    def _record(self, level: str, msg: str, *args: Any, **_kw: Any) -> None:
        try:
            text = msg % args if args else msg
        except Exception:  # noqa: BLE001
            text = str(msg)
        self.lines.append((level, text))

    def info(self, msg: str, *a: Any, **k: Any) -> None: self._record("INFO", msg, *a, **k)
    def warning(self, msg: str, *a: Any, **k: Any) -> None: self._record("WARN", msg, *a, **k)
    def error(self, msg: str, *a: Any, **k: Any) -> None: self._record("ERROR", msg, *a, **k)
    def debug(self, msg: str, *a: Any, **k: Any) -> None: self._record("DEBUG", msg, *a, **k)

    def text(self) -> str:
        return "\n".join("%s %s" % (lvl, msg) for lvl, msg in self.lines)


class FakeContext:
    def __init__(self, plugin_data_dir: Path) -> None:
        self.registered_web_apis: list[tuple[str, Any, list[str], str]] = []
        self.plugin_data_dir = plugin_data_dir
        self.sent: list[tuple[str, MessageChain]] = []
        self.send_should_fail = False

    def register_web_api(self, route: str, handler: Any, methods: list[str], desc: str) -> None:
        # 上游要求路由以 /<plugin_name>/ 开头，违反就直接抛错
        if not route.startswith("/" + PLUGIN_NAME + "/"):
            raise ValueError("插件 Web API 路由必须以 /%s/ 开头，收到: %s" % (PLUGIN_NAME, route))
        self.registered_web_apis.append((route, handler, methods, desc))

    async def send_message(self, session: str, message_chain: MessageChain) -> bool:
        if self.send_should_fail:
            return False
        self.sent.append((session, message_chain))
        return True

    def handler_for(self, suffix: str, method: str | None = None) -> Any:
        for route, handler, methods, _ in self.registered_web_apis:
            if not route.endswith(suffix):
                continue
            if method is not None and method.upper() not in [m.upper() for m in methods]:
                continue
            return handler
        raise KeyError("未注册的路由: %s" % suffix)

    def routes(self) -> list[str]:
        return [r for r, _, _, _ in self.registered_web_apis]


class FakeStar:
    def __init__(self, context: Any = None, config: Any = None) -> None:
        self.context = context


class FakeEvent:
    """对照 AstrMessageEvent 里插件真正用到的那部分。"""

    def __init__(
        self,
        message_str: str = "",
        *,
        umo: str = "aiocqhttp:FriendMessage:10001",
        components: list[Any] | None = None,
        self_id: str = "99999",
        sender_id: str = "10001",
        sender_name: str = "tester",
        group_id: str = "",
        is_private: bool = True,
        platform_id: str = "aiocqhttp",
        platform_name: str = "aiocqhttp",
    ) -> None:
        self.message_str = message_str
        self.unified_msg_origin = umo
        self._components = list(components or [])
        self._self_id = self_id
        self._sender_id = sender_id
        self._sender_name = sender_name
        self._group_id = group_id
        self._is_private = is_private
        self._platform_id = platform_id
        self._platform_name = platform_name
        self.call_llm = False
        self.stopped = False

    def get_messages(self) -> list[Any]:
        return list(self._components)

    def get_self_id(self) -> str: return self._self_id
    def get_sender_id(self) -> str: return self._sender_id
    def get_sender_name(self) -> str: return self._sender_name
    def get_group_id(self) -> str: return self._group_id
    def get_platform_id(self) -> str: return self._platform_id
    def get_platform_name(self) -> str: return self._platform_name
    def is_private_chat(self) -> bool: return self._is_private

    def should_call_llm(self, value: bool) -> None:
        # 上游这个方法只是 self.call_llm = value，而管线里是「not event.call_llm」
        # 才去请求 LLM —— 也就是传 True 反而是禁止默认 LLM 请求。
        self.call_llm = value

    def stop_event(self) -> None:
        self.stopped = True

    def plain_result(self, text: str) -> tuple[str, str]:
        return ("plain", text)


# ------------------------------------------------------------------ 装载


class EventMessageType(enum.Flag):
    GROUP_MESSAGE = enum.auto()
    PRIVATE_MESSAGE = enum.auto()
    OTHER_MESSAGE = enum.auto()
    ALL = GROUP_MESSAGE | PRIVATE_MESSAGE | OTHER_MESSAGE


def install(plugin_data_dir: Path) -> tuple[FakeLogger, FakeContext]:
    logger = FakeLogger()
    context = FakeContext(plugin_data_dir)

    def mod(name: str, **attrs: Any) -> types.ModuleType:
        m = types.ModuleType(name)
        for k, v in attrs.items():
            setattr(m, k, v)
        sys.modules[name] = m
        parent, _, leaf = name.rpartition(".")
        if parent and parent in sys.modules:
            setattr(sys.modules[parent], leaf, m)
        return m

    commands: list[Any] = []
    message_handlers: list[Any] = []

    def command(name: str | None = None, **_k: Any):
        def deco(fn):
            fn.__is_command__ = True
            fn.__command_name__ = name or fn.__name__
            commands.append(fn)
            return fn
        return deco

    def event_message_type(*_a: Any, **_k: Any):
        def deco(fn):
            message_handlers.append(fn)
            return fn
        return deco

    filter_ns = types.SimpleNamespace(
        command=command,
        llm_tool=lambda *a, **k: (lambda fn: fn),
        event_message_type=event_message_type,
        EventMessageType=EventMessageType,
    )

    event_mod = mod(
        "astrbot.api.event",
        filter=filter_ns,
        AstrMessageEvent=FakeEvent,
        MessageChain=MessageChain,
    )
    # 插件用 from astrbot.api.event.filter import EventMessageType
    mod(
        "astrbot.api.event.filter",
        EventMessageType=EventMessageType,
        command=command,
        event_message_type=event_message_type,
    )

    mod("astrbot")
    mod("astrbot.api", logger=logger)
    mod("astrbot.api.star", Context=FakeContext, Star=FakeStar)
    mod(
        "astrbot.api.message_components",
        Plain=Plain, At=At, AtAll=AtAll, Image=Image,
    )
    mod(
        "astrbot.api.web",
        request=None,  # 由 set_request 注入
        json_response=json_response,
        error_response=error_response,
        file_response=file_response,
        stream_response=stream_response,
        PluginMultiDict=PluginMultiDict,
        PluginRequest=PluginRequest,
    )
    mod("astrbot.core")
    mod("astrbot.core.utils")
    mod(
        "astrbot.core.utils.astrbot_path",
        get_astrbot_plugin_data_path=lambda: str(plugin_data_dir),
    )

    context.message_handlers = message_handlers  # type: ignore[attr-defined]
    context.commands = commands  # type: ignore[attr-defined]
    return logger, context


def load_plugin(config: dict | None = None, module_name: str = "dsh_bridge_under_test"):
    """加载插件模块并实例化插件类。"""
    if module_name in sys.modules:
        del sys.modules[module_name]
    spec = importlib.util.spec_from_file_location(module_name, PLUGIN_MAIN)
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)

    plugin_data_dir = Path(sys.modules["astrbot.core.utils.astrbot_path"].get_astrbot_plugin_data_path())
    context = FakeContext(plugin_data_dir)
    instance = module.DshBridgePlugin(context=context, config=config or {})
    return module, instance, context


def set_request(module: types.ModuleType, request: PluginRequest) -> None:
    """把当前请求注入插件模块（插件用 from astrbot.api.web import request）。"""
    module.request = request


def auth_headers(plugin: Any, ttl: int = 300) -> dict[str, str]:
    import time as _time

    exp = int(_time.time()) + ttl
    return {"X-DSH-Bridge-Token": "%d.%s" % (exp, plugin._sign(exp))}
