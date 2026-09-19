# DietCam · 拍照饮食管理

用手机拍照片、或直接打字描述 → 送到你自己的视觉大模型做营养分析 → 结果**以文件形式归档到 AstrBot 服务器**，
不占用、也不污染任何聊天上下文；需要时用 `/饮食` 指令主动查看日报和汇总。

## 为什么这样设计

大多数"拍照问 AI"的方案会把每张照片都塞进对话上下文，聊几天上下文就爆了，还得靠压缩。
这个项目反过来：**照片和分析结果只落盘归档，聊天里一条消息都不产生**。
你可以随时用 `/饮食` 把某天/某周/某月的汇总"取"出来看，看完即走。

## 两种用法

| 模式 | 需要 AstrBot | 照片与记录放在哪 | 适合谁 |
| --- | --- | --- | --- |
| **连 AstrBot**（默认） | 需要 | AstrBot 服务器；手机只留一份最近看过的图缓存 | 自己用，想在聊天里 `/饮食` 查 |
| **本地模式** | **不需要** | 全部在这台手机上 | 给朋友用，或只想自己记账 |

安装后在「设置」最上方切换。两种模式界面完全一样，随时可换（但数据不互通）。

### 本地模式

直接调用你自己填的 OpenAI 兼容视觉模型接口，完全不经过 AstrBot：

- 接口地址写到 `/v1` 即可，程序自动补 `/chat/completions`
- 模型名称可以点「拉取模型列表」直接从接口 `GET /models` 拉下来挑，不用手打
- 模型必须能看图（qwen2.5-vl / gpt-4o / glm-4v 等）
- 记录在 App 私有目录的 `diet/records/<日期>.jsonl`，照片在 `diet/photos/<日期>/`
- 数据不离开手机，卸载即清空，注意自行备份

## 架构

```
┌──────────────┐   拍照    ┌───────────────────────────────┐
│  Android APP │ ────────► │  AstrBot 服务器 (公网)         │
│  CameraX     │  HTTP     │                               │
│  Compose     │  multipart│  astrbot_plugin_diet          │
└──────────────┘           │   ├─ 落盘 photos/<日期>/       │
                           │   ├─ 调用视觉模型分析          │
                           │   ├─ 追加 records/<日期>.jsonl │
                           │   └─ /饮食 指令读取汇总        │
                           └───────────┬───────────────────┘
                                       │ OpenAI 兼容 /v1/chat/completions
                                       ▼
                            你的多模态模型（本地或云端）
```

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `astrbot_plugin_diet/` | AstrBot 插件：接收上传、落盘、调用模型、提供 `/饮食` 指令 |
| `android/` | Android 客户端（Kotlin + Jetpack Compose + CameraX） |
| `android/keystore/dietcam-release.jks` | **固定签名密钥库**，保证每次构建的包都能覆盖升级 |
| `android/keystore.properties` | 签名配置（密钥库路径与口令）——**只该留在本机**，见「签名与升级」 |
| `tools/` | 构建工具链安装、插件自测、演示、版本号递增脚本 |
| `dist/` | 本地打包产物（`*.apk` 在 .gitignore 里，不进版本库） |
| `.github/workflows/build-apk.yml` | GitHub Actions：自动编译 APK 并上传产物 |

## 快速部署

完整步骤见 [docs/部署指南.md](docs/部署指南.md)，核心就四步：

1. **装插件**：把 `astrbot_plugin_diet/` 整个目录放到 AstrBot 的 `data/plugins/` 下并重载插件。
2. **配插件**：在 WebUI 插件配置里填写 `base_url`、`model`、`hmac_secret`（签名密钥，自己定一个随机串）。
3. **建 API Key**：WebUI → 设置 → API Key，权限**勾选 `plugin`**，复制 `abk_...`。
   > 这是 **AstrBot 自己的钥匙**，不是 OpenAI 的 key。调用模型的 key 填在插件配置里。
4. **装 APP**：用 GitHub Actions 产物或自行编译的 APK，填入 `服务器地址 / AstrBot API Key / 签名密钥`，然后开拍。

## 使用

### 主页

打开 APP 就是主页：当日摄入 / 每日目标的进度环、热量剩余、三大营养素进度条，
下面是今天的逐条记录（带缩略图）。底部的绿色圆钮进入相机。

### 四个页签

| 页签 | 内容 |
| --- | --- |
| **主页** | 当日进度环、营养素进度条、今日记录（点任意一条弹出完整详情） |
| **日历** | 月历视图，有记录的日子带小圆点（绿=达标 / 黄=偏少 / 红=超标），下方本月概况 |
| **回忆** | 最近 7/30/90 天的记录按日期串成时间线 |
| **我的** | 身体档案（身高/体重/年龄/性别/活动量/目标）→ 自动推算每日目标，也可手动改目标 |

### 记录

| 操作 | 结果 |
| --- | --- |
| 拍一张餐食照片 | 按下快门后**画面定格**，可以确认或重拍；确认后分析并归档 |
| 确认照片后 | **刚拍的**原图会另存一份到系统相册（`Pictures/DietCam/`），在手机自带相册里也能翻到；点「重拍」丢掉的那张不会存进相册 |
| 从相册选图 | 直接打开**系统相册**（`ACTION_PICK`），而不是文件管理器；选来的图本来就在相册里，**不会**再往回存一份 |
| **在输入框打字后按 ➤** | **不拍照也能记录**，直接描述吃了什么即可分析（如"中午吃了一碗牛肉面"） |
| 打字 + 拍照 | 文字作为补充说明一起交给模型，估算更准（如"这是一人份""少油"） |
| 分析过程中 | 实时显示模型正在输出的内容，随时可以**中断** |
| **点任意一条记录** | 弹出详情卡：照片、热量、三大营养素、分项明细、建议、备注一次看全（可滚动，不再被截断），底部是**修改 / 重新分析 / 删除**三个操作；点照片可全屏看原图 |
| **重新分析** | 分析期间弹窗进入「处理中」：转圈 + 禁用按钮，急走可点「先关掉（后台继续）」；结束后给出**前后对照**，例如「热量　620 → 480 千卡（-140）」，一眼看出模型改了什么 |
| 修改 / 删除 | 同样有「保存中…」「删除中…」状态，不会静默等待 |

### 聊天指令

| 指令 | 结果 |
| --- | --- |
| `/饮食` | 今天的饮食日报 |
| `/饮食 昨天` | 昨天的日报（也认「前天」） |
| `/饮食 2026-06-27` | 指定日期（`6-27` 也认） |
| `/饮食 本周` | 最近 7 天汇总（含日均） |
| `/饮食 最近14天` | 任意天数汇总 |
| `/饮食 本月` | 当月汇总 |
| `/饮食 清理` | 删掉今天没有对应记录的孤儿照片 |
| `/饮食 清理全部` | 清理所有日期的孤儿照片 |
| `/饮食状态` | 插件状态、数据目录、LLM 工具、今日记录数与孤儿照片数 |

### 让模型自己查（LLM 工具）

插件注册了三个**只读**工具，模型在回答前会自己去翻记录，你不用敲指令：

| 工具 | 能回答 |
| --- | --- |
| `diet_query_day` | 「我今天吃了多少」「昨天晚饭是什么」 |
| `diet_query_range` | 「这周蛋白质够吗」「本月日均热量」 |
| `diet_search` | 「上次吃鸡翅是什么时候」「这周喝奶茶了吗」 |

于是在聊天里直接问就行，例如「帮我看看这周碳水是不是偏高」。

> 说明：只有模型真的调用了工具时，那一小段查询结果才会进入对话上下文；
> 日常拍照记录依旧是纯文件落盘，不产生任何聊天消息。

## 数据存放位置

所有数据都在 AstrBot 的插件数据目录下（默认 `data/plugin_data/astrbot_plugin_diet/`）：

```
astrbot_plugin_diet/
├── photos/2026-06-27/143210_a1b2c3d4.jpg   归档照片
├── records/2026-06-27.jsonl                当天记录（一行一条 JSON）
└── state.json                              最近会话标识等状态
```

纯文字记录不产生照片文件，在 jsonl 里表现为 `photo` 为空、`source` 为 `app-text`、
`note` 保留你的原始描述；日报里用 ✍️ 与照片记录（📷）区分。

`records/` 和 `photos/` 理论上应当一一对应。如果照片比记录多（例如分析中途失败、
模型报错、用户点了中断），插件会：

1. 分析失败或中断时**立刻删掉**那张还没成记录的照片；
2. 在手机端删除记录时，顺带清理当天遗留的孤儿照片；
3. 提供 `/饮食 清理全部` 和 `POST /cleanup` 手动收尾。

> 刚上传 60 秒内的照片不会被自动清理，避免误删正在分析的那张。

想换存储位置，直接把这个目录软链到你想要的地方即可。

## 签名与版本（避免"安装"而非"升级"）

Android 判断能否**覆盖升级**，看三件事同时满足：

1. `applicationId` 相同（本项目的 `com.dietcam.app`）
2. 新包的签名与已安装版本**证书一致**
3. 新包的 `versionCode` **更大**

这三条对应到项目里的做法：

**① 签名固定下来了。** 密钥库提交在仓库里（`android/keystore/dietcam-release.jks`），
本地构建和 GitHub Actions 都用它签名，证书指纹固定为：

```
CN=DietCam, OU=Personal, O=DietCam, C=CN
SHA-256: D7:6D:49:A7:C1:70:63:E6:69:CA:28:9E:7F:24:F5:EF:E7:D2:27:4A:DB:0E:AA:89:9A:FF:9B:A1:04:8B:5C:26
```

> 之前用 debug 签名是错的：debug 证书由每台机器/每次 CI 随机生成，指纹必然不同，
> 换台机器出的包只能卸载重装。现在已改为固定 release 密钥库。
> 密钥库有效期 10000 天（约 27 年），不会中途过期导致无法升级。

**② 版本号有工具管。** 发布新版前跑一次：

```bash
python tools/bump_version.py 1.0.1     # versionCode 自动 +1，versionName 设为 1.0.1
python tools/bump_version.py --show    # 查看当前版本
```

只改 `versionName` 不顶用——系统只认 `versionCode`。

**③ 首次从旧包迁移。** 如果你已经装过之前那个 debug 签名的版本，
因为它和新证书不同，**需要卸载一次**再装新包；从此以后就能一路覆盖升级了。

⚠️ 密钥库和口令一旦更换，所有用户都必须卸载重装。别丢、别改。

**关于口令的存放。** 密钥库（`.jks`）**必须留在仓库里** —— 覆盖升级靠它，
换掉它等于让所有人卸载重装。但**口令不该跟着进版本库**：`.gitignore` 和
`android/keystore.properties.example` 已经准备好了，需要时两步移出：

```bash
git rm --cached android/keystore.properties   # 停止跟踪，本机文件保留
git commit -m "chore: 签名口令移出版本库"
```

> ⚠️ 两点要有数：① 这一步会**同时从别人机器上删掉那份文件**，
> 同伴拉取后需要照 `keystore.properties.example` 自己补一份；
> ② 旧口令仍然留在 git 历史里，真正止血得换口令 —— 换口令**不会改变签名证书**
> （指纹不变、升级链不断），但同属「动了就别丢」的操作，建议单独安排一次。
> GitHub Actions 侧已改成从 `KEYSTORE_PROPERTIES` secret 读取，没配则显式告警。



## 安全说明

- 上传接口除了 API Key，还要求 `X-Diet-Token`：由 `HMAC-SHA256(签名密钥, 过期时间戳)` 生成，5 分钟有效。
  两把"钥匙"分别放在 AstrBot 和 APP 里，任一泄漏都不足以被滥用。
- 建议无论如何都放在 HTTPS 反代之后，并考虑用防火墙把端口限制到自己的 IP 段。

## 已验证

不是"写完就交"，下面这些是实际跑出来的结果：

**AstrBot 插件 — 296 项测试全部通过**

```bash
python tools/run_all_checks.py      # 一键跑完下面全部检查
```

| 检查 | 说明 |
| --- | --- |
| `tools/plugin_selftest.py` | 296 项单元 + 端到端测试 |
| `tools/verify_regression.py` | 验证测试本身有效（能区分修复前后） |
| `tools/demo_plugin.py` | 端到端演示，兼作冒烟测试 |

测试跑在 `tools/astrbot_stub.py` 上——一个**忠实模拟** AstrBot 的桩，
关键在于它复刻了 `PluginMultiDict` **不是 dict 子类**这一事实
（早期用普通 dict 冒充请求对象，导致一个真实 bug 被测试掩盖，详见下节）。

覆盖范围：模型输出 JSON 解析、记录构造与数值容错、jsonl 读写、营养汇总、
HMAC 鉴权（合法/篡改/过期/缺失/空签名/换密钥）、路径穿越防护、餐次推断、
日报与区间渲染、配置容错，以及**六个接口的完整端到端调用**：
正常路径、缺字段、空文件、超大文件、非法参数、无凭据、错误字段名。

接口调用方式逐条对照过 AstrBot 源码验证：
`astrbot.api.web` 的 `json_response / error_response / file_response` 签名、
`PluginUploadFile.save()`、`request.headers / query / form / files`，
以及插件路由挂载于 `/api/v1/plugins/extensions/{plugin_name}` 且需 `plugin` scope 的 API Key。

**端到端演示 — 在模拟 AstrBot 环境里真跑一遍**

```bash
python tools/demo_plugin.py
```

会合成一张炒饭照片、走完"上传 → 落盘 → 分析 → 查询 → 取回"全流程，
并打印 `/饮食` 指令的真实输出与磁盘文件结构，不需要任何模型服务。

**已修复的真实问题（2.6.2 / 插件 1.3.2）**

一份外部代码走查（`DietCam-代码走查.md`）逐行读完后，下面这些都被复现并修掉了。
共同点是**本地模式（不连 AstrBot）没被当成一等公民** —— 它是后来加的，
几处判断还停留在「必须有 baseUrl/secret」的旧假设上。

| 问题 | 根因 | 用户看到的现象 |
| --- | --- | --- |
| 本地模式下「档案 / 日历 / 回忆」三页永远空白 | 三个 `refresh*()` 用 `baseUrl`/`secret` 判空早退，而本地模式这两个字段本来就不填 | 给人用的那条路直接残废 |
| 本地模式下「保存并重算目标」点了没反应 | `saveProfile()` 的 `return` 排在 `_busy = true` 之前，静默返回 | 以为按钮坏了 |
| 回忆页选「90 天」实际只给 30 天 | `/history` 给只收 3 个参数的 `_pick()` 多传了 `type=int`，抛出的 `TypeError` 被紧邻的 `except` 吞掉 | 以为已经看全了 |
| 本地模式手动改目标会把其它三项清零 | `LocalDietApi.updateTargets` 整份替换 `targets`（插件版一直是先读再 merge） | 只填热量 → 蛋白/碳水/脂肪变 0 |
| 点「切换摄像头」画面黑掉 | `AndroidView` 的 `factory` 只跑一次，`DisposableEffect(lensFacing)` 只 `unbindAll()` 不重新绑定 | 切前后摄即黑屏 |
| 拍照失败、存相册失败全程无声 | 失败分支只 `file.delete()`，没有回传路径 | 不知道发生了什么 |
| 「关于」页版本号写死 `2.1.0` | 与真实构建脱节（早已 2.6.x） | 报障时被这个数字带偏 |

同步补上的测试：`/history` 的 `days` 现在逐个断言「传 7 就回 7、传 90 就回 90」——
之前只断言 `days <= 365`，于是「参数整个失效」和一整套全绿测试可以同时成立，
**测试替实现圆了谎**；本地模式的目标合并语义则由上面那组 JVM 单元测试守着。

**已修复的真实问题（2.1.0）**

上线后又踩到两个，都已修复并加了回归验证：

* `too many values to unpack (expected 2)` —— 在 `astrbot_provider`（复用 AstrBot 提供商）模式下
  点拍照必现。根因是流式分支里把 `_analyze()` 返回的 **dict** 当成元组解包
  （`text, engine = await self._analyze(...)`），dict 键多于两个就抛错。
* 相机页底栏跑到屏幕顶部并盖住返回按钮 —— `Crossfade` 的内容 lambda 不是 `BoxScope`，
  在里面写 `Modifier.align()` 不生效。已改为在内容外包一层铺满的 `Box`。
* 主页「今天」被刘海遮挡 —— `LazyColumn` 缺少 `statusBarsPadding()`。

`tools/verify_regression.py` 会把这两个 bug 的旧写法分别还原到副本上跑一遍，
确认测试确实能复现它们（复现结果：`400 缺少文件字段 file`、
`too many values to unpack (expected 2)`）。

**已修复的真实问题（1.0.3）**

上线后点击拍照报 `缺少文件字段 file`。根因是 AstrBot 的 `PluginMultiDict`
**不是 `dict` 子类**，而插件里用了 `isinstance(files, dict)` 判断——恒为 False，
导致上传的文件和备注被静默丢弃。同一处写法还让「拍照+备注」的备注从未送达模型。

修复方式是不再判断类型，改用鸭子类型取值（`_pick`）。
`tools/verify_regression.py` 会把这个 bug 还原到一份副本上，
确认测试确实能复现它（复现结果：`400 缺少文件字段 file`）。

**Android 端 — 真实编译出包**

```
BUILD SUCCESSFUL in 38s
50 actionable tasks: 8 executed, 42 up-to-date
```

| 项目 | 结果 |
| --- | --- |
| 产物 | `dist/DietCam-2.6.4-release.apk`（12.44 MB） |
| 包名 / 版本 | `com.dietcam.app` v2.6.4 (versionCode 15) |
| SDK | minSdk 26，targetSdk 35，compileSdk 35 |
| 权限 | CAMERA、INTERNET、ACCESS_NETWORK_STATE（+ API≤28 的 WRITE_EXTERNAL_STORAGE） |
| 启动 Activity | `com.dietcam.app.MainActivity` |
| 签名 | 固定 release 证书 `CN=DietCam`（非 debug） |
| 签名方案 | `apksigner verify` 通过，证书与历史包完全一致 |
| 证书指纹 | `d76d49a7c17063e669ca289e7f24f5efe7d2274adb0eaa899aff9ba1048b5c26`（跨构建稳定） |
| SHA-256 | `068A8D950F41D693D3C44B963928090F39C2C33AC4A7A98619DA8667DA07D626` |

**升级路径实测** —— 每次发版都由 `bump_version.py` 递增 versionCode 后构建，
证书指纹始终是上面那一串：

| | 1.0.0 | 2.6.4 |
| --- | --- | --- |
| applicationId | `com.dietcam.app` | `com.dietcam.app` ✅ 一致 |
| versionCode | 1 | 15 ✅ 更大 |
| 签名证书 | `CN=DietCam` `d76d49a7…` | `CN=DietCam` `d76d49a7…` ✅ 完全一致 |

三个条件同时满足，因此新包会被系统识别为**升级**而不是新装。

工具链：Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.1.0 + Compose BOM 2024.12.01。
本地构建用 JDK 21，GitHub Actions 用 JDK 17（`tools/build_android.ps1` 接受 JDK 17 及以上），两者编出的包完全一致。

**本地模式数据层自检 —— 在电脑上跑真实的 LocalDietApi**

```bash
pwsh -File tools/jvmcheck/run.ps1
```

本地模式（不连 AstrBot）只有真机能点，出问题之前只能靠猜。`tools/jvmcheck/` 给
`android.content / graphics / util` 补了**最小**桩（多一个成员都不加，免得掩盖真机差异），
于是真实的 `LocalDietApi.kt` 可以在普通 JVM 上编译并运行，把
「存档案 → 重算目标 → 首页汇总 / 日历 / 历史 → 编辑删除 → 流式分片解析」整条路跑一遍 —— 62 项断言。

它绕开了 Gradle 的 `test` 任务：那个会 fork 测试 JVM 走本地 socket，
在受限环境里会挂住；这里直接调 kotlinc + java。另有 `TargetsMergeTest`
（`tools/build_android.ps1 -Task testReleaseUnitTest`）守着目标合并语义。

`tools/jvmcheck/LocalModeCheck.kt` 与 `tools/plugin_selftest.py` 里各有一组
**锚点断言，钉的是同一组数值**（180cm/80kg/35岁/中度/减脂 → 2176.2 千卡、
蛋白 136 g、碳水 272 g）——保证 Kotlin 与 Python 两份 `compute_targets` 不会各算各的。