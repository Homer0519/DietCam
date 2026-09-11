# -*- coding: utf-8 -*-
"""发布新版本：自动递增 versionCode 并可选更新 versionName。

Android 判断"升级"的依据是 (applicationId, versionCode)：
同一个包名、新包 versionCode **更大**、且签名一致，才会被当成升级覆盖安装。
只改 versionName 是不够的。

用法：
    python tools/bump_version.py              # versionCode +1
    python tools/bump_version.py 1.0.1        # versionCode +1 并把 versionName 设为 1.0.1
    python tools/bump_version.py --show       # 只看当前版本
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"


def read_current(text: str) -> tuple[int, str]:
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        raise SystemExit("没能在 build.gradle.kts 里找到 versionCode / versionName")
    return int(code.group(1)), name.group(1)


def main() -> int:
    args = [a for a in sys.argv[1:] if a.strip()]
    text = GRADLE.read_text(encoding="utf-8")
    code, name = read_current(text)

    if "--show" in args:
        print("applicationId : com.dietcam.app")
        print("versionCode   : %d" % code)
        print("versionName   : %s" % name)
        return 0

    new_code = code + 1
    new_name = name
    for a in args:
        if not a.startswith("--"):
            new_name = a

    text = re.sub(r"versionCode\s*=\s*\d+", "versionCode = %d" % new_code, text, count=1)
    text = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "%s"' % new_name, text, count=1)
    GRADLE.write_text(text, encoding="utf-8")

    print("已更新版本：")
    print("  versionCode : %d -> %d" % (code, new_code))
    print("  versionName : %s -> %s" % (name, new_name))
    print()
    print("接着构建并覆盖安装即可，系统会识别为升级而不是新装：")
    print("  pwsh -File tools/build_android.ps1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
