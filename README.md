# DietCam · 拍照饮食管理

用手机拍照片、或直接打字描述 → 送到你自己的视觉大模型做营养分析 → 结果**以文件形式归档到 AstrBot 服务器**，
不占用、也不污染任何聊天上下文；需要时用 `/饮食` 指令主动查看日报和汇总。

## 为什么这样设计

大多数"拍照问 AI"的方案会把每张照片都塞进对话上下文，聊几天上下文就爆了，还得靠压缩。
这个项目反过来：**照片和分析结果只落盘归档，聊天里一条消息都不产生**。
你可以随时用 `/饮食` 把某天/某周/某月的汇总"取"出来看，看完即走。

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
| `android/keystore.properties` | 签名配置（密钥库路径与口令） |
| `.github/workflows/build-apk.yml` | GitHub Actions：自动编译 APK 并上传产物 |
| `tools/` | 构建工具链安装、插件自测、演示、版本号递增脚本 |
| `dist/` | 已编译好的 APK |

## 快速部署

完整步骤见 [docs/部署指南.md](docs/部署指南.md)，核心就四步：

1. **装插件**：把 `astrbot_plugin_diet/` 整个目录放到 AstrBot 的 `data/plugins/` 下并重载插件。
2. **配插件**：在 WebUI 插件配置里填写 `base_url`、`model`、`hmac_secret`（签名密钥，自己定一个随机串）。
3. **建 API Key**：WebUI → 设置 → API Key，权限**勾选 `plugin`**，复制 `abk_...`。
   > 这是 **AstrBot 自己的钥匙**，不是 OpenAI 的 key。调用模型的 key 填在插件配置里。
4. **装 APP**：用 GitHub Actions 产物或自行编译的 APK，填入 `服务器地址 / AstrBot API Key / 签名密钥`，然后开拍。

## 使用

| 操作 | 结果 |
| --- | --- |
| 拍一张餐食照片 | 自动分析并以卡片显示热量与三大营养素，同时归档到当天 |
| **在输入框打字后按 ➤** | **不拍照也能记录**，直接描述吃了什么即可分析（如"中午吃了一碗牛肉面"） |
| 打字 + 拍照 | 文字作为补充说明一起交给模型，估算更准（如"这是一人份""少油"） |
| 在输入框打字后按 ➤ | **不拍照也能记录**，直接描述吃了什么即可分析（如"中午吃了一碗牛肉面"） |
| 打字 + 拍照 | 文字会作为补充说明一起交给模型，估算更准（如"这是一人份"） |
| `/饮食` | 今天的饮食日报 |
| `/饮食 昨天` | 昨天的日报 |
| `/饮食 2026-06-27` | 指定日期 |
| `/饮食 本周` | 最近 7 天汇总（含日均） |
| `/饮食 本月` | 当月汇总 |
| `/饮食状态` | 插件运行状态、数据目录、今日记录数 |

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

## 安全说明

- 上传接口除了 API Key，还要求 `X-Diet-Token`：由 `HMAC-SHA256(签名密钥, 过期时间戳)` 生成，5 分钟有效。
  两把"钥匙"分别放在 AstrBot 和 APP 里，任一泄漏都不足以被滥用。
- 建议无论如何都放在 HTTPS 反代之后，并考虑用防火墙把端口限制到自己的 IP 段。

## 已验证

不是"写完就交"，下面这些是实际跑出来的结果：

**AstrBot 插件 — 107 项测试全部通过**

```bash
python tools/run_all_checks.py      # 一键跑完下面全部检查
```

| 检查 | 说明 |
| --- | --- |
| `tools/plugin_selftest.py` | 107 项单元 + 端到端测试 |
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

**已修复的真实问题（1.0.3）**

上线后点击拍照报 `缺少文件字段 file`。根因是 AstrBot 的 `PluginMultiDict`
**不是 `dict` 子类**，而插件里用了 `isinstance(files, dict)` 判断——恒为 False，
导致上传的文件和备注被静默丢弃。同一处写法还让「拍照+备注」的备注从未送达模型。

修复方式是不再判断类型，改用鸭子类型取值（`_pick`）。
`tools/verify_regression.py` 会把这个 bug 还原到一份副本上，
确认测试确实能复现它（复现结果：`400 缺少文件字段 file`）。

**Android 端 — 真实编译出包**

```
BUILD SUCCESSFUL in 34s
49 actionable tasks: 30 executed, 18 from cache, 1 up-to-date
```

| 项目 | 结果 |
| --- | --- |
| 产物 | `dist/DietCam-1.0.2-release.apk`（12.24 MB） |
| 包名 / 版本 | `com.dietcam.app` v1.0.2 (versionCode 3) |
| SDK | minSdk 26，targetSdk 35，compileSdk 35 |
| 权限 | CAMERA、INTERNET、ACCESS_NETWORK_STATE |
| 启动 Activity | `com.dietcam.app.MainActivity` |
| 签名 | 固定 release 证书 `CN=DietCam`（非 debug） |
| 签名方案 | APK Signature Scheme v2 + v3 均已校验通过 |
| 证书指纹 | `d76d49a7c17063e669ca289e7f24f5efe7d2274adb0eaa899aff9ba1048b5c26`（跨构建稳定） |
| SHA-256 | `932C85F4443505F2AB2A68341CE5D65871801489CAF1BADC48AB5485D5E8C00F` |

**升级路径实测** —— 1.0.2（文字输入 + 连接报错提示）由 `bump_version.py` 递增后构建：

| | 1.0.0 | 1.0.2 |
| --- | --- | --- |
| applicationId | `com.dietcam.app` | `com.dietcam.app` ✅ 一致 |
| versionCode | 1 | 3 ✅ 更大 |
| 签名证书 | `CN=DietCam` `d76d49a7…` | `CN=DietCam` `d76d49a7…` ✅ 完全一致 |

三个条件同时满足，因此新包会被系统识别为**升级**而不是新装。

工具链：JDK 21 + Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.1.0 + Compose BOM 2024.12.01。
