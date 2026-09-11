# -*- coding: utf-8 -*-
"""Bootstrap a local Android build toolchain inside .toolchain/ (workspace-local)."""
import os, sys, ssl, zipfile, subprocess, shutil, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TC = os.path.join(ROOT, ".toolchain")
DL = os.path.join(TC, "downloads")
SDK = os.path.join(TC, "android-sdk")
GRADLE_DIR = os.path.join(TC, "gradle")
os.makedirs(DL, exist_ok=True)

CT_URL = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
GRADLE_VER = "8.11.1"
GR_URL = "https://services.gradle.org/distributions/gradle-%s-bin.zip" % GRADLE_VER

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}

def log(*a):
    print(*a, flush=True)

def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 1000:
        log("[skip] already downloaded", os.path.basename(dest), os.path.getsize(dest))
        return dest
    log("[get ]", url)
    req = urllib.request.Request(url, headers=UA)
    tmp = dest + ".part"
    with urllib.request.urlopen(req, context=CTX, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        got = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            got += len(chunk)
            if total:
                log("   %5.1f%%  %.1f/%.1f MB" % (got * 100.0 / total, got / 1e6, total / 1e6))
    os.replace(tmp, dest)
    log("[ok  ]", dest, os.path.getsize(dest))
    return dest

def unzip(src, dest, strip=None):
    log("[unzip]", src, "->", dest)
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(src) as z:
        names = z.namelist()
        top = sorted(set(n.split("/")[0] for n in names if n.strip()))
        for n in names:
            if n.endswith("/"):
                continue
            parts = n.split("/")
            if strip and top and parts[0] == top[0] and len(parts) > 1:
                parts = parts[1:]
            out = os.path.join(dest, *parts)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            if os.path.exists(out) and os.path.getsize(out) > 0:
                continue
            with z.open(n) as s, open(out, "wb") as t:
                shutil.copyfileobj(s, t)
    log("[ok  ] unzipped")

def java_home():
    jh = os.environ.get("JAVA_HOME")
    if jh and os.path.exists(os.path.join(jh, "bin", "java.exe")):
        return jh
    for base in (r"C:\Program Files\Microsoft", r"C:\Program Files\Java", r"C:\Program Files\Eclipse Adoptium"):
        if os.path.isdir(base):
            for name in sorted(os.listdir(base), reverse=True):
                p = os.path.join(base, name)
                if os.path.exists(os.path.join(p, "bin", "java.exe")):
                    return p
    raise SystemExit("JAVA_HOME not found")

JH = java_home()
log("[env ] JAVA_HOME =", JH)
env = dict(os.environ)
env["JAVA_HOME"] = JH
env["PATH"] = os.path.join(JH, "bin") + os.pathsep + env.get("PATH", "")

# 1) command line tools
ct_zip = os.path.join(DL, "cmdline-tools.zip")
if not os.path.exists(os.path.join(SDK, "cmdline-tools", "latest", "bin", "sdkmanager.bat")):
    download(CT_URL, ct_zip)
    unzip(ct_zip, os.path.join(SDK, "cmdline-tools", "latest"), strip=True)
else:
    log("[skip] cmdline-tools present")

SDKM = os.path.join(SDK, "cmdline-tools", "latest", "bin", "sdkmanager.bat")
log("[sdk ] sdkmanager at", SDKM)

# 2) licenses
log("[sdk ] accepting licenses")
lic = subprocess.run([SDKM, "--sdk_root=" + SDK, "--licenses"], input=("y\r\n" * 60).encode(),
                     env=env, capture_output=True)
log(lic.stdout.decode("utf-8", "replace")[-1500:])
log(lic.stderr.decode("utf-8", "replace")[-800:])

# 3) packages
pkgs = ["platform-tools", "platforms;android-35", "build-tools;35.0.0"]
log("[sdk ] installing", pkgs)
ins = subprocess.run([SDKM, "--sdk_root=" + SDK] + pkgs, input=("y\r\n" * 60).encode(),
                     env=env, capture_output=True)
log(ins.stdout.decode("utf-8", "replace")[-3000:])
log(ins.stderr.decode("utf-8", "replace")[-1500:])
log("[sdk ] exit", ins.returncode)

# 4) gradle
gradle_bin = os.path.join(GRADLE_DIR, "gradle-%s" % GRADLE_VER, "bin", "gradle.bat")
if not os.path.exists(gradle_bin):
    gz = os.path.join(DL, "gradle-%s-bin.zip" % GRADLE_VER)
    download(GR_URL, gz)
    unzip(gz, GRADLE_DIR, strip=True)
else:
    log("[skip] gradle present")

log("[done] toolchain ready")
log("ANDROID_HOME=" + SDK)
log("GRADLE=" + gradle_bin)
