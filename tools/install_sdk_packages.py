# -*- coding: utf-8 -*-
"""Install required Android SDK packages by resolving the official repository XML."""
import os, ssl, zipfile, shutil, urllib.request, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.path.join(ROOT, ".toolchain", "android-sdk")
DL = os.path.join(ROOT, ".toolchain", "downloads")
os.makedirs(DL, exist_ok=True)

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}

WANT = {
    "platforms;android-35": ("platforms", "android-35"),
    "build-tools;35.0.0": ("build-tools", "35.0.0"),
    "platform-tools": (None, "platform-tools"),
}

REPOS = [
    "https://dl.google.com/android/repository/repository2-3.xml",
    "https://dl.google.com/android/repository/repository2-1.xml",
]

def log(*a): print(*a, flush=True)

def get(url, dest=None):
    req = urllib.request.Request(url, headers=UA)
    if dest is None:
        with urllib.request.urlopen(req, context=CTX, timeout=120) as r:
            return r.read()
    if os.path.exists(dest) and os.path.getsize(dest) > 1000:
        log("[skip]", os.path.basename(dest))
        return dest
    log("[get ]", url)
    tmp = dest + ".part"
    with urllib.request.urlopen(req, context=CTX, timeout=300) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0); got = 0
        while True:
            c = r.read(1 << 20)
            if not c: break
            f.write(c); got += len(c)
            if total and got % (20 << 20) < (1 << 20):
                log("   %5.1f%%" % (got * 100.0 / total))
    os.replace(tmp, dest)
    log("[ok  ]", dest, os.path.getsize(dest))
    return dest

def find_packages():
    """Return {path: (url, size)} for the packages we want."""
    found = {}
    for repo in REPOS:
        try:
            raw = get(repo)
            root = ET.fromstring(raw)
        except Exception as e:
            log("[warn] repo failed", repo, e); continue
        for pkg in root.iter():
            path = pkg.get("path")
            if not path or path not in WANT or path in found:
                continue
            url = None
            for arch in pkg.iter("archive"):
                u = arch.find("complete/url")
                if u is not None and u.text:
                    host = arch.findtext("host-os")
                    if host in (None, "windows"):
                        url = "https://dl.google.com/android/repository/" + u.text.strip()
                        break
            if url:
                found[path] = url
                log("[find]", path, "->", url)
    return found

def extract_into(zip_path, target_dir, expect_name):
    tmp = target_dir + "__tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(tmp)
    entries = os.listdir(tmp)
    log("[zip ] top-level:", entries)
    src = None
    for name in entries:
        if name.lower().startswith(expect_name.lower()):
            src = os.path.join(tmp, name); break
    if src is None:
        src = os.path.join(tmp, entries[0]) if len(entries) == 1 else tmp
    shutil.rmtree(target_dir, ignore_errors=True)
    os.makedirs(os.path.dirname(target_dir), exist_ok=True)
    shutil.move(src, target_dir)
    shutil.rmtree(tmp, ignore_errors=True)
    log("[ok  ] installed ->", target_dir)

found = find_packages()
missing = [p for p in WANT if p not in found]
if missing:
    log("[ERROR] could not resolve:", missing)

for path, url in found.items():
    sub, name = WANT[path]
    target = os.path.join(SDK, sub, name) if sub else os.path.join(SDK, name)
    if os.path.isdir(target) and os.listdir(target):
        log("[skip] already installed", target)
        continue
    zp = os.path.join(DL, os.path.basename(url))
    get(url, zp)
    pkgdir = os.path.dirname(target)
    extract_into(zp, target, name if not name.startswith("android-") else name)

# licenses so the Android Gradle Plugin accepts the SDK
lic_dir = os.path.join(SDK, "licenses")
os.makedirs(lic_dir, exist_ok=True)
licenses = {
    "android-sdk-license": "8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n",
    "android-sdk-preview-license": "84831b9409646a918e30573bab4c9c91346d8abd\n",
    "android-googletv-license": "601085b94cd77f0b54ff86406957099ebe79c4d6\n",
    "android-sdk-arm-dbt-license": "859f317696f67ef3d7f30a50a5560e7834b43903\n",
}
for k, v in licenses.items():
    with open(os.path.join(lic_dir, k), "w", encoding="utf-8") as f:
        f.write(v)

log("")
log("=== verify ===")
for p in ["platforms/android-35", "build-tools/35.0.0", "platform-tools"]:
    d = os.path.join(SDK, *p.split("/"))
    log(p, "exists" if os.path.isdir(d) else "MISSING", os.listdir(d)[:8] if os.path.isdir(d) else "")
log("done")
