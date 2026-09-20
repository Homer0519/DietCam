$ErrorActionPreference = "Stop"
# ---------------------------------------------------------------------------
# 本地模式数据层自检：把**真实的** LocalDietApi 放到普通 JVM 上编译并运行。
#
# 两个不得不这么做的理由：
#  1) 本地模式只有真机能点，出问题只能靠猜 —— 这里能在电脑上把
#     「存档案 → 重算目标 → 汇总 / 日历 / 历史」完整跑一遍。
#  2) 不用 Gradle 的 test 任务：它会 fork 测试 JVM 走本地 socket，
#     在受限环境里会挂住；这里直接调 kotlinc + java，不依赖那个。
#
# 注意：PowerShell 5.1 传给 java.exe 的参数走 ANSI 编码，**中文路径会解析失败**，
# 所以全程走一个 ASCII 的目录联接（junction）。
# ---------------------------------------------------------------------------
$D = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

$J = Join-Path $env:TEMP "dietcam-jvmcheck"
if (-not (Test-Path $J)) {
    New-Item -ItemType Junction -Path $J -Target $D | Out-Null
    Write-Host "==> 已建立 ASCII 联接: $J" -ForegroundColor DarkGray
}

$java = "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot\bin\java.exe"
if (-not (Test-Path $java)) { $java = "java" }

$g = "$J\.toolchain\gradle-home\caches\modules-2\files-2.1"
function Find-Jar($grp, $name) {
    $p = Get-ChildItem "$g\$grp\$name" -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
    if (-not $p) { throw ("Gradle 缓存里找不到 " + $grp + ":" + $name + "（先跑一次 Android 构建）") }
    return $p
}

$compiler = Find-Jar "org.jetbrains.kotlin" "kotlin-compiler-embeddable"
$jars = @(
    (Find-Jar "org.jetbrains.kotlin" "kotlin-stdlib"),
    (Find-Jar "com.squareup.okhttp3" "okhttp"),
    (Find-Jar "com.squareup.okio" "okio-jvm"),
    (Find-Jar "org.jetbrains.kotlinx" "kotlinx-coroutines-core-jvm")
)
$json = "$J\.toolchain\testlibs\json-20240303.jar"
if (-not (Test-Path $json)) {
    Write-Host "==> 下载 org.json（供 JVM 上顶替 Android 内置的那个）" -ForegroundColor DarkGray
    New-Item -ItemType Directory -Force -Path (Split-Path $json) | Out-Null
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar" -OutFile $json -UseBasicParsing
}
$jars += $json
$cp = $jars -join ";"

# kotlin-compiler-embeddable 自己还需要 trove4j / reflect / script-runtime
$compilerExtra = @()
foreach ($spec in @(
    @("org.jetbrains", "annotations"),
    @("org.jetbrains.intellij.deps", "trove4j"),
    @("org.jetbrains.kotlin", "kotlin-reflect"),
    @("org.jetbrains.kotlin", "kotlin-script-runtime")
)) {
    $p = Get-ChildItem "$g\$($spec[0])\$($spec[1])" -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
    if ($p) { $compilerExtra += $p }
}
$compilerCp = (@($compiler) + $compilerExtra + $jars) -join ";"

$src = "$J\android\app\src\main\java\com\dietcam\app"
$files = @(
    "$src\Model.kt",
    "$src\VersionCompare.kt",
    "$src\Sanitize.kt",
    "$src\SettingsStore.kt",
    "$src\DietBackend.kt",
    "$src\DietApi.kt",
    "$src\LocalDietApi.kt",
    "$J\tools\jvmcheck\app-stubs.kt",
    "$J\tools\jvmcheck\android-content-stubs.kt",
    "$J\tools\jvmcheck\android-graphics-stubs.kt",
    "$J\tools\jvmcheck\android-util-stubs.kt",
    "$J\tools\jvmcheck\LocalModeCheck.kt"
)
foreach ($f in $files) { if (-not (Test-Path $f)) { throw "缺少源文件: $f" } }

$out = "$J\.toolchain\jvmcheck-out"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

Set-Location $J

Write-Host "==> 编译（真实的 LocalDietApi + 最小 android 桩）" -ForegroundColor Cyan
& $java -cp "$compilerCp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -nowarn -classpath "$cp" -d "$out" @files
if ($LASTEXITCODE -ne 0) { throw "编译失败" }

Write-Host "==> 运行自检" -ForegroundColor Cyan
& $java -cp "$out;$cp" jvmcheck.LocalModeCheckKt
exit $LASTEXITCODE
