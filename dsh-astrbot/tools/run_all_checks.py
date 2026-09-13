# -*- coding: utf-8 -*-
"""一键跑完两端的检查。

    python tools/run_all_checks.py

注意：Node 测试是直接用 node 跑的，没有走 node --test。
因为 --test 会为每个文件 spawn 一个子进程并用管道收输出，在某些受限环境
（管道被禁）里会直接 EPERM 失败；直接跑文件则是同一进程内执行测试，等价且更省事。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def run(title: str, cmd: list[str]) -> int:
    print("=" * 68)
    print(title)
    print("=" * 68)
    # 不捕获输出：让子进程直接继承 stdio，避免在受限环境里被管道权限卡住
    proc = subprocess.run(cmd, cwd=str(ROOT))
    print()
    return proc.returncode


def main() -> int:
    checks = [
        ("AstrBot 侧插件自测 (python)", [sys.executable, str(ROOT / "tools" / "test_astrbot_plugin.py")]),
        ("DSH 侧插件测试 (node)", ["node", str(ROOT / "tools" / "dsh-plugin.test.mjs")]),
        ("打包契约检查 (node)", ["node", str(ROOT / "tools" / "verify-package.mjs")]),
        ("真 cordis 加载冒烟 (node)", ["node", str(ROOT / "tools" / "cordis-smoke.mjs")]),
    ]
    failures = []
    for title, cmd in checks:
        if run(title, cmd) != 0:
            failures.append(title)

    print("=" * 68)
    if failures:
        print("失败：%s" % "、".join(failures))
        return 1
    print("全部检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
