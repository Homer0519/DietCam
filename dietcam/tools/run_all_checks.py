# -*- coding: utf-8 -*-
"""一键跑完所有检查。

    python tools/run_all_checks.py

依次执行：
    1. 插件语法检查
    2. 插件单元 + 端到端测试（107 项）
    3. 回归测试有效性验证
    4. 端到端演示（顺带当作冒烟测试）
全部通过才返回 0。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
PY = sys.executable

STEPS = [
    ("插件语法检查", [PY, "-c",
      "import ast,pathlib;ast.parse(pathlib.Path(r'%s').read_text(encoding='utf-8'));print('语法 OK')"
      % (ROOT / "astrbot_plugin_diet" / "main.py")]),
    ("插件测试套件", [PY, str(TOOLS / "plugin_selftest.py")]),
    ("回归测试有效性", [PY, str(TOOLS / "verify_regression.py")]),
    ("端到端演示", [PY, str(TOOLS / "demo_plugin.py")]),
]


def main() -> int:
    results = []
    for name, cmd in STEPS:
        print()
        print("#" * 62)
        print("# " + name)
        print("#" * 62)
        proc = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        out = (proc.stdout or "") + (proc.stderr or "")
        # 演示输出很长，只保留开头与结尾
        lines = out.splitlines()
        if name == "端到端演示" and len(lines) > 26:
            shown = lines[:12] + ["   ...（中间省略）..."] + lines[-12:]
        else:
            shown = lines
        print("\n".join(shown))
        results.append((name, proc.returncode))

    print()
    print("=" * 62)
    print(" 汇总")
    print("=" * 62)
    failed = 0
    for name, code in results:
        mark = "通过" if code == 0 else "失败(exit %d)" % code
        if code != 0:
            failed += 1
        print("  %-18s %s" % (name, mark))
    print()
    if failed:
        print("%d 项检查未通过。" % failed)
        return 1
    print("全部检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
