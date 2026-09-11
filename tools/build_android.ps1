<#
    DietCam 本地构建脚本。
    首次运行会自动下载 Android SDK 与 Gradle 到 .toolchain/（约 500MB）。

    用法：
        pwsh -File tools/build_android.ps1
        pwsh -File tools/build_android.ps1 -Task assembleDebug
#>
param(
    [string]$Task = "assembleRelease",
    [switch]$Bootstrap
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# 找一个可用的 JDK
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    $candidates = @()
    foreach ($base in @("C:\Program Files\Microsoft", "C:\Program Files\Java",
                        "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Android\Android Studio\jbr")) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
                Select-Object -ExpandProperty FullName
        }
    }
    if (-not $candidates) {
        throw "找不到 JDK。请安装 JDK 17 或更高版本，并设置 JAVA_HOME。"
    }
    $javaHome = $candidates[-1]
}
$env:JAVA_HOME = $javaHome

$sdk = Join-Path $Root ".toolchain\android-sdk"
$gradle = Join-Path $Root ".toolchain\gradle\bin\gradle.bat"

if ($Bootstrap -or -not (Test-Path $gradle) -or -not (Test-Path (Join-Path $sdk "platforms"))) {
    Write-Host "==> 正在准备构建工具链（首次需要几分钟）" -ForegroundColor Cyan
    & python (Join-Path $PSScriptRoot "bootstrap_android.py")
    & python (Join-Path $PSScriptRoot "install_sdk_packages.py")
}

$env:ANDROID_HOME = (Resolve-Path $sdk).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = (Resolve-Path (Join-Path $Root ".toolchain")).Path + "\gradle-home"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null

Write-Host "==> JAVA_HOME   = $env:JAVA_HOME"
Write-Host "==> ANDROID_HOME= $env:ANDROID_HOME"
Write-Host "==> 开始构建 $Task" -ForegroundColor Cyan

& $gradle -p (Join-Path $Root "android") $Task --no-daemon --console=plain
$code = $LASTEXITCODE

$apkDir = Join-Path $Root "android\app\build\outputs\apk"
if (Test-Path $apkDir) {
    Write-Host "==> 产物：" -ForegroundColor Green
    Get-ChildItem $apkDir -Recurse -Filter *.apk | ForEach-Object {
        Write-Host ("    {0}  ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB))
    }
}
exit $code
