# -*- coding: utf-8 -*-
"""忠实模拟 AstrBot 运行环境的测试桩。

为什么需要它：早先的测试用普通 dict 冒充请求对象，结果掩盖了一个真实 bug ——
AstrBot 的 PluginMultiDict 并不是 dict 的子类，而插件里用 isinstance(x, dict)
判断，导致上传的文件被静默丢弃，线上表现为「缺少文件字段 file」。

因此本桩刻意复刻上游的关键行为（尤其是「不是 dict」这一点），
并提供一个 meta 测试来守护这个特性。

对照的上游实现：astrbot/api/web.py（PluginMultiDict / PluginUploadFile / PluginRequest）
"""
from __future__ import annotations

import importlib.util
import sys
import types
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_MAIN = ROOT / "astrbot_plugin_diet" / "main.py"


# --------------------------------------------------------------------------
# 请求侧：严格复刻上游语义
# --------------------------------------------------------------------------

class PluginMultiDict:
    """对照 astrbot.api.web.PluginMultiDict。

    注意：**故意不继承 dict**。上游也是如此，依赖 isinstance(x, dict) 的代码
    会在这里失败，从而在测试阶段就暴露问题。
    """

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


class PluginUploadFile:
    """对照 astrbot.api.web.PluginUploadFile（只保留插件用到的部分）。"""

    def __init__(self, filename: str, data: bytes, content_type: str = "image/jpeg") -> None:
        self.filename = filename
        self.content_type = content_type
        self._data = data

    @property
    def size(self) -> int:
        return len(self._data)

    async def save(self, destination: str | Path) -> None:
        path = Path(destination)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self._data)


class PluginRequest:
    """对照 astrbot.api.web.PluginRequest。

    query / form() / files() 一律返回 PluginMultiDict，与上游一致。
    """

    def __init__(
        self,
        *,
        method: str = "POST",
        headers: dict[str, str] | None = None,
        query: dict[str, Any] | list[tuple[str, Any]] | None = None,
        form: dict[str, str] | list[tuple[str, str]] | None = None,
        files: dict[str, PluginUploadFile] | list[tuple[str, PluginUploadFile]] | None = None,
        payload: Any = None,
        path_params: dict[str, Any] | None = None,
        plugin_name: str = "astrbot_plugin_diet",
        username: str | None = None,
    ) -> None:
        def to_pairs(value):
            if value is None:
                return []
            if isinstance(value, dict):
                return list(value.items())
            return list(value)

        self.method = method
        self.headers = dict(headers or {})
        self.query = PluginMultiDict(to_pairs(query))
        self.path_params = dict(path_params or {})
        self.plugin_name = plugin_name
        self.username = username
        self.path = "/api/v1/plugins/extensions/astrbot_plugin_diet"
        self.content_type = self.headers.get("content-type")
        self.client_host = "127.0.0.1"
        self._form = PluginMultiDict(to_pairs(form))
        self._files = PluginMultiDict(to_pairs(files))
        self._payload = payload

    async def body(self) -> bytes:
        return b""

    async def json(self, default: Any = None) -> Any:
        return self._payload if self._payload is not None else default

    async def form(self) -> PluginMultiDict:
        return self._form

    async def files(self) -> PluginMultiDict:
        return self._files


# --------------------------------------------------------------------------
# 响应侧
# --------------------------------------------------------------------------

class FakeResponse:
    def __init__(self, kind: str, payload: Any, status_code: int) -> None:
        self.kind = kind
        self.payload = payload
        self.status_code = status_code

    def __repr__(self) -> str:
        return "<FakeResponse %s %s>" % (self.status_code, self.kind)


def json_response(data: Any = None, *, status_code: int = 200, **_: Any) -> FakeResponse:
    return FakeResponse("json", {} if data is None else data, status_code)


def error_response(message: str, *, status_code: int = 400, data: Any = None, **_: Any) -> FakeResponse:
    return FakeResponse("error", {"message": message, "data": data}, status_code)


class FakeStreamResponse(FakeResponse):
    """对照 stream_response 的返回值。

    payload 是异步生成器；测试里用 await resp.collect() 把全部块取出来。
    """

    def __init__(self, content: Any, content_type: str = "text/event-stream") -> None:
        super().__init__("stream", content, 200)
        self.content_type = content_type

    async def chunks(self) -> list[str]:
        out: list[str] = []
        async for piece in self.payload:
            out.append(piece if isinstance(piece, str) else piece.decode("utf-8", "replace"))
        return out

    async def text(self) -> str:
        return "".join(await self.chunks())

    async def events(self) -> list[dict]:
        """把 SSE 文本解析成事件对象列表。"""
        import json as _json

        parsed: list[dict] = []
        for block in (await self.text()).split("\n\n"):
            for line in block.splitlines():
                if not line.startswith("data:"):
                    continue
                raw = line[5:].strip()
                try:
                    parsed.append(_json.loads(raw))
                except _json.JSONDecodeError:
                    parsed.append({"_raw": raw})
        return parsed


def stream_response(content: Any, *, content_type: str = "text/event-stream", status_code: int = 200, **_: Any) -> FakeStreamResponse:
    return FakeStreamResponse(content, content_type)


def file_response(path: Any, *, filename: str | None = None, content_type: str | None = None, **_: Any) -> FakeResponse:
    return FakeResponse("file", str(path), 200)


# --------------------------------------------------------------------------
# 插件宿主：Context / 事件 / logger
# --------------------------------------------------------------------------

class _Logger:
    def __init__(self) -> None:
        self.lines: list[tuple[str, str]] = []

    def _record(self, level: str, msg: str, *args: Any) -> None:
        self.lines.append((level, msg % args if args else msg))

    def info(self, msg: str, *a: Any) -> None: self._record("INFO", msg, *a)
    def warning(self, msg: str, *a: Any) -> None: self._record("WARN", msg, *a)
    def error(self, msg: str, *a: Any, **k: Any) -> None: self._record("ERROR", msg, *a)
    def debug(self, msg: str, *a: Any) -> None: self._record("DEBUG", msg, *a)


class FakeContext:
    """假的 AstrBot Context，记录注册的 Web API 并能按路由派发请求。"""

    def __init__(self, plugin_data_dir: Path) -> None:
        self.registered_web_apis: list[tuple[str, Any, list[str], str]] = []
        self.plugin_data_dir = plugin_data_dir

    def register_web_api(self, route: str, handler: Any, methods: list[str], desc: str) -> None:
        self.registered_web_apis.append((route, handler, methods, desc))

    # 便于测试：按路径后缀直接调用对应 handler
    # 同一个路径可能有多个方法（例如 /profile 的 GET 与 POST），
    # 所以支持用 method 精确指定。
    def handler_for(self, suffix: str, method: str | None = None) -> Any:
        for route, handler, methods, _ in self.registered_web_apis:
            if not route.endswith(suffix):
                continue
            if method is not None and method.upper() not in [m.upper() for m in methods]:
                continue
            return handler
        raise KeyError("未注册的路由: %s (method=%s)" % (suffix, method))

    def routes(self) -> list[str]:
        return [r for r, _, _, _ in self.registered_web_apis]


class FakeEvent:
    def __init__(self, message_str: str, umo: str = "webchat:FriendMessage:test") -> None:
        self.message_str = message_str
        self.unified_msg_origin = umo

    def plain_result(self, text: str) -> tuple[str, str]:
        return ("plain", text)

    def get_sender_name(self) -> str:
        return "tester"


class FakeStar:
    def __init__(self, context: Any = None, config: Any = None) -> None:
        self.context = context


# --------------------------------------------------------------------------
# 安装与加载
# --------------------------------------------------------------------------

def install(plugin_data_dir: Path) -> tuple[_Logger, FakeContext]:
    """把假的 astrbot 包塞进 sys.modules，返回 (logger, context)。"""
    logger = _Logger()
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

    def command(*_a: Any, **_k: Any):
        def deco(fn):
            fn.__is_command__ = True
            return fn
        return deco

    mod("astrbot")
    mod("astrbot.api", logger=logger)
    mod("astrbot.api.event", filter=types.SimpleNamespace(command=command), AstrMessageEvent=FakeEvent)
    mod("astrbot.api.star", Context=FakeContext, Star=FakeStar)
    mod(
        "astrbot.api.web",
        request=None,  # 由 set_request 注入
        json_response=json_response,
        error_response=error_response,
        file_response=file_response,
        stream_response=stream_response,
        PluginMultiDict=PluginMultiDict,
        PluginUploadFile=PluginUploadFile,
        PluginRequest=PluginRequest,
    )
    mod("astrbot.core")
    mod("astrbot.core.utils")
    mod(
        "astrbot.core.utils.astrbot_path",
        get_astrbot_plugin_data_path=lambda: str(plugin_data_dir),
    )

    if "aiohttp" not in sys.modules:
        try:
            import aiohttp  # noqa: F401
        except ImportError:
            class _Timeout:
                def __init__(self, **kw: Any) -> None: pass

            class _Session:
                def __init__(self, **kw: Any) -> None: pass
                async def __aenter__(self): return self
                async def __aexit__(self, *a: Any) -> bool: return False

            mod("aiohttp", ClientTimeout=_Timeout, ClientSession=_Session)

    return logger, context


def load_plugin(config: dict | None = None, module_name: str = "diet_main_under_test"):
    """加载插件模块并实例化插件类。"""
    import importlib

    web = sys.modules["astrbot.api.web"]
    if module_name in sys.modules:
        del sys.modules[module_name]
    spec = importlib.util.spec_from_file_location(module_name, PLUGIN_MAIN)
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    context = None
    # 找到刚 install 的 context（FakeContext 实例在 star 模块里定义）
    plugin_data_dir = Path(sys.modules["astrbot.core.utils.astrbot_path"].get_astrbot_plugin_data_path())
    context = FakeContext(plugin_data_dir)
    instance = module.DietPlugin(context=context, config=config or {})
    return module, instance, context


def set_request(module: types.ModuleType, request: PluginRequest) -> None:
    """把当前请求注入插件模块（插件用 from astrbot.api.web import request）。"""
    module.request = request


def auth_headers(plugin: Any, ttl: int = 300) -> dict[str, str]:
    """生成插件认可的 X-Diet-Token。"""
    import time as _time

    exp = int(_time.time()) + ttl
    return {"X-Diet-Token": "%d.%s" % (exp, plugin._sign(exp))}
