# -*- coding: utf-8 -*-
"""验证测试本身是否有效（「测试的测试」）。

把修复前的写法还原到一份插件副本上，用同一套请求对象跑一遍上传：

    修复前 -> 应该复现 400 缺少文件字段 file
    修复后 -> 应该返回 200

如果哪一天这个脚本显示「修复前也能通过」，说明测试桩变得过于宽松、
或者有人改回了 isinstance(x, dict) 这类写法，需要立刻检查。
"""
from __future__ import annotations

import asyncio
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import astrbot_stub as stub  # noqa: E402

ROOT = HERE.parent
SOURCE = ROOT / "astrbot_plugin_diet" / "main.py"
WORK = ROOT / ".selftest_data" / "regression"
if WORK.exists():
    shutil.rmtree(WORK)
WORK.mkdir(parents=True, exist_ok=True)

FIXED_BLOCK = '''        upload = _pick(files, "file")
        if upload is None and files:
            # 客户端用了别的字段名也认，尽量别让它白跑一趟
            try:
                upload = next(iter(files.values()))
            except (StopIteration, TypeError):
                upload = None'''
BUGGY_BLOCK = '''        upload = None
        if isinstance(files, dict):
            upload = files.get("file")
            if upload is None and files:
                upload = next(iter(files.values()))'''

source = SOURCE.read_text(encoding="utf-8")
if FIXED_BLOCK not in source:
    print("!! 找不到修复后的代码块，请更新 tools/verify_regression.py")
    sys.exit(2)

broken_path = WORK / "main.py"
broken_path.write_text(source.replace(FIXED_BLOCK, BUGGY_BLOCK), encoding="utf-8")


def run_case(label: str, path: Path) -> str:
    stub.PLUGIN_MAIN = path
    data_dir = WORK / ("data_" + label)
    if data_dir.exists():
        shutil.rmtree(data_dir)
    data_dir.mkdir(parents=True, exist_ok=True)

    stub.install(data_dir)
    module, plugin, context = stub.load_plugin(
        {"base_url": "http://x/v1", "model": "m", "hmac_secret": "s"},
        module_name="regr_" + label,
    )

    async def fake(path_arg, prompt):
        return '{"is_food":true,"title":"炒饭","calories_kcal":700}', "stub"

    plugin._analyze_openai = fake
    stub.set_request(
        module,
        stub.PluginRequest(
            headers=dict(stub.auth_headers(plugin)),
            files={"file": stub.PluginUploadFile("meal.jpg", b"\xff\xd8fake")},
        ),
    )
    resp = asyncio.run(context.handler_for("/analyze")())
    return "200 OK" if resp.status_code == 200 else "%s %s" % (resp.status_code, resp.payload.get("message"))


print("=" * 60)
print(" 回归测试有效性验证")
print("=" * 60)
print()
before = run_case("before", broken_path)
print("修复前的写法  -> %s" % before)
after = run_case("after", SOURCE)
print("修复后的写法  -> %s" % after)
print()

ok = True
if "缺少文件字段" in before:
    print("  [OK] 修复前的代码确实复现了线上错误")
else:
    print("  [!!] 没能复现，根因判断可能有误：%s" % before)
    ok = False

if after == "200 OK":
    print("  [OK] 修复后同一请求返回 200")
else:
    print("  [!!] 修复后仍然失败：%s" % after)
    ok = False

print()
if ok:
    print("结论：测试用例能区分修复前后，回归测试有效。")
    sys.exit(0)
print("结论：验证未通过。")
sys.exit(1)
