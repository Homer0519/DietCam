# -*- coding: utf-8 -*-
"""打包插件为可直接投放的 zip。

生成的压缩包解压后顶层就是 astrbot_plugin_diet/ 目录，
因此可以直接解压进 AstrBot 的 data/plugins/ 使用。

用法：
    python tools/package_plugin.py
"""
from __future__ import annotations

import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "astrbot_plugin_diet"
DIST = ROOT / "dist"

SKIP_DIRS = {"__pycache__", ".git", ".idea", ".venv"}
SKIP_SUFFIX = {".pyc", ".pyo", ".log", ".zip"}


def read_version() -> str:
    text = (PLUGIN_DIR / "metadata.yaml").read_text(encoding="utf-8")
    m = re.search(r"^version:\s*(\S+)", text, re.M)
    return m.group(1) if m else "0.0.0"


def collect() -> list[Path]:
    files: list[Path] = []
    for path in sorted(PLUGIN_DIR.rglob("*")):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.suffix.lower() in SKIP_SUFFIX:
            continue
        files.append(path)
    return files


def main() -> int:
    DIST.mkdir(parents=True, exist_ok=True)
    version = read_version()
    files = collect()
    target = DIST / ("astrbot_plugin_diet-v%s.zip" % version)

    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in files:
            # 统一用正斜杠，保证在任何系统解压出来的结构都一致
            arcname = path.relative_to(ROOT).as_posix()
            z.write(path, arcname)

    print("已打包: %s" % target.relative_to(ROOT))
    print("版本:   %s" % version)
    print("大小:   %.1f KB" % (target.stat().st_size / 1024))
    print()
    print("压缩包内容:")
    with zipfile.ZipFile(target) as z:
        for info in z.infolist():
            print("  %-46s %6d B" % (info.filename, info.file_size))
        bad = z.testzip()
    print()
    print("完整性校验: %s" % ("通过" if bad is None else "损坏于 " + str(bad)))
    print()
    print("用法：解压后把 astrbot_plugin_diet/ 整个目录放进 AstrBot 的 data/plugins/ 下，重载插件。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
