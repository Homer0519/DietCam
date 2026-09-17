# AstrBot 插件开发仓库

这个仓库用来放多个 AstrBot 相关的插件项目，**每个项目一个子目录**，互不干扰。

| 目录 | 项目 | 说明 |
| --- | --- | --- |
| `dietcam/` | **DietCam 饮食管理** | 拍照 / 打字记录饮食的 AstrBot 插件 + 配套 Android 客户端 |

---

## dietcam/

| 子目录 | 内容 |
| --- | --- |
| `dietcam/astrbot_plugin_diet/` | AstrBot 插件本体（接收上传、落盘、调用视觉模型、`/饮食` 指令、LLM 工具） |
| `dietcam/android/` | Android 客户端（Kotlin + Compose + CameraX），支持「连 AstrBot」与「纯本地」两种模式 |
| `dietcam/tools/` | 构建与测试工具（插件自测、端到端演示、Android 构建脚本、版本号递增） |
| `dietcam/docs/` | 部署指南 |
| `dietcam/dist/` | 本地打包产物（`*.apk` / `*.zip` 在 .gitignore 里，不入库） |

完整说明见 **[dietcam/README.md](dietcam/README.md)**，
其中出现的相对路径一律以 `dietcam/` 为根。

### 快速开始

```bash
# 插件自测 + 端到端演示（不需要模型服务）
python dietcam/tools/run_all_checks.py

# 打插件包
python dietcam/tools/package_plugin.py

# 编译 Android 客户端（首次会自动把 SDK 下到 dietcam/.toolchain/）
pwsh -File dietcam/tools/build_android.ps1

# 发新版：versionCode 自动 +1
python dietcam/tools/bump_version.py 2.6.3
```

发布产物在 GitHub Releases：<https://github.com/Homer0519/DietCam/releases/latest>

---

## 本仓库的约定

- **新增项目**：在根目录建一个子目录，再到 `.gitignore` 的「按项目分组」区域照抄一段规则。
- **CI**：`.github/workflows/` 里的工作流用 `paths:` 圈定各自项目的路径，
  免得改 A 项目触发 B 项目的构建。
- **不写死绝对路径**：各项目的构建脚本一律基于自身位置推导
  （Python 用 `__file__`，PowerShell 用 `$PSScriptRoot`），
  所以整个子目录可以随便挪位置。
