# dsh-astrbot

把**公网 AstrBot** 接到**本机 DeepSeek Harness**：你在 QQ / 微信 / Telegram / 飞书 / Discord 里说一句话，
本机的 DSH 干活，结果回到原来的会话。

**方向是 DSH 主动连出去**（HTTP 长轮询），所以 DSH 不需要公网 IP、不需要内网穿透，
放在家用宽带 / NAT 后面就能用。

---

## 和 dsh-im 的关系

[dsh-im](https://github.com/xmanrui/dsh-im) 是这类项目里做得最完整的：一个 DSH 插件 + **11 个渠道适配器**，
每个渠道自己实现收发、卡片、引用、文件、线程。它的主机端打包出来 8.5 MB，是有原因的。

本项目走的是另一条路：**AstrBot 已经把 QQ / 微信 / Telegram / 飞书 / Discord / Slack / 钉钉 这些渠道都包好了**。
所以这里根本不需要写 11 个适配器，只需要「一个渠道」——AstrBot 本身。

两种做法对照：

| | dsh-im | dsh-astrbot |
| --- | --- | --- |
| 渠道接入 | 每个平台一个适配器，逐个打磨 | 交给 AstrBot，DSH 只跟 AstrBot 说话 |
| 通信方向 | 各平台回调 / 长连接 | DSH → 公网 AstrBot，HTTP 长轮询 |
| 需要 DSH 有公网入口 | 视渠道而定 | **不需要** |
| 代码量 | 主机端 8.5 MB 打包产物 | 约 1500 行（含测试与文档） |
| 平台原生卡片 / 按钮 | 各渠道深度适配 | 纯文本（渠道能力由 AstrBot 决定） |

一句话：**如果你已经在用 AstrBot，这个项目用很小的代价就能让 DSH 触手可及；
如果你要的是各平台原生的卡片交互，dsh-im 更合适。**

---

## 架构

```
┌──────────────┐   QQ / 微信 / Telegram / 飞书 …   ┌──────────────────────────────┐
│   你的手机    │ ────────────────────────────────► │  公网 AstrBot                │
│              │ ◄──────────────────────────────── │  astrbot_plugin_dsh_bridge   │
└──────────────┘          回复原路返回              │   ├─ 收件 → 带游标的队列     │
                                                   │   ├─ /inbox  长轮询取件      │
                                                   │   ├─ /reply  把结果发回会话  │
                                                   │   └─ /health /reset /whoami  │
                                                   └───────────▲──────────────────┘
                                                               │  DSH 主动连出去
                                                               │  HTTP 长轮询 + 双钥匙鉴权
                                                   ┌───────────┴──────────────────┐
                                                   │  本机 DeepSeek Harness       │
                                                   │  dsh-astrbot 插件            │
                                                   │   ├─ 轮询取件                │
                                                   │   ├─ 每个会话绑一个 Harness 会话│
                                                   │   ├─ 驱动 agent、收集回复     │
                                                   │   └─ 审批 / 提问转到聊天里    │
                                                   └──────────────────────────────┘
```

### 为什么 AstrBot 侧必须装一个插件

AstrBot 的 Open API 里**有**主动发消息的接口（`POST /api/v1/im/messages`），
但**没有**「把收到的消息推给我」这种接口；而插件注册的 HTTP 路由又被挂载点强制要求
plugin scope 的 API Key。所以「发」可以借用 Open API，「收」这一半只能在 AstrBot 里落一个插件。

另外 AstrBot 插件的 Web API **没法免掉 API Key**（`require_plugin_scope` 在挂载时就生效了），
所以本项目用**两把钥匙**：AstrBot 的 API Key 负责过挂载点，插件自己的 HMAC 共享密钥负责过插件这关。

---

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `astrbot_plugin_dsh_bridge/` | **AstrBot 插件**（Python）。整个目录丢进 AstrBot 的 `data/plugins/` 即可 |
| `dsh-plugin/` | **DSH 插件**（Node ESM，cordis 插件）。装到 DSH 的 profile 里 |
| `tools/astrbot_stub.py` | 忠实模拟 AstrBot 的测试桩（复刻 PluginMultiDict 不是 dict、路由前缀校验等怪癖） |
| `tools/test_astrbot_plugin.py` | AstrBot 侧 132 项自测 |
| `tools/fake-astrbot.mjs` | **真的会说 HTTP** 的假 AstrBot，用来端到端验证 DSH 侧 |
| `tools/dsh-plugin.test.mjs` | DSH 侧 36 项测试（含端到端） |
| `tools/verify-package.mjs` | 打包契约检查：镜像 DSH 安装器的质量门禁（不许声明官方包、不许有未声明的裸 import、patch 行必须能解析） |
| `tools/cordis-smoke.mjs` | **用真的 @deepseek-ai/cordis 加载本插件**，验证接线（inject 门禁、事件总线、waterfall、释放）；环境里没有 cordis 就明确跳过 |
| `tools/run_all_checks.py` | 一键跑完上面全部 |
| `docs/协议.md` | 两端之间的 HTTP 协议定义 |
| `docs/部署指南.md` | 从零到跑通的完整步骤 |

---

## 快速开始

完整步骤见 [docs/部署指南.md](docs/部署指南.md)，核心四步：

1. **装 AstrBot 插件**：把 `astrbot_plugin_dsh_bridge/` 整个目录放到 AstrBot 的 `data/plugins/` 下，重载插件。
2. **配 AstrBot 插件**：WebUI 插件配置里填 `hmac_secret`（自己随机生成一串）。
3. **建 API Key**：WebUI → 设置 → API Key，权限勾选 `plugin`，复制 `abk_...`。
   > 这是 **AstrBot 自己的钥匙**，不是模型 key。
4. **装 DSH 插件**：把 `dsh-plugin/` 装进 DSH profile，在配置里填 `baseUrl / apiKey / secret`。

然后在 QQ 里给机器人发一条私聊消息试试。

---

## AstrBot 侧配置

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否收件 |
| `hmac_secret` | 空 | **必填**。和 DSH 侧 `secret` 必须一致 |
| `forward_mode` | `direct` | `direct` = 私聊 + 群里 @机器人；`all` = 群里所有消息 |
| `suppress_default_llm` | `true` | 接管时不让 AstrBot 自己的大模型再回一遍（只影响 LLM，不影响别的插件） |
| `stop_event` | `false` | 更彻底地终止事件传播（会连带屏蔽其它插件，有冲突时才开） |
| `forward_images` | `true` | 图片一起转发给 DSH |
| `max_image_bytes` | 8 MiB | 单张图片体积上限 |
| `max_images` | 4 | 单条消息最多几张图 |
| `max_queue` | 200 | 待取队列上限，超出丢最旧的 |
| `max_poll_seconds` | 55 | 单次长轮询最长挂起秒数 |
| `max_message_chars` | 1500 | 单条回复上限，超出按段落切分成多条 |
| `debug` | `false` | 调试日志 |

## DSH 侧配置

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `baseUrl` | 空 | **必填**。站点根地址即可，会自动补上插件挂载路径 |
| `apiKey` | 空 | AstrBot 的 API Key（`abk_...`）。不填会 401 |
| `secret` | 空 | **必填**。和 AstrBot 侧 `hmac_secret` 一致 |
| `workspace` | DSH 当前目录 | Harness 会话的工作目录 |
| `agentPreset` | 空 | 指定 agent preset |
| `allowUsers` | `[]` | 只允许这些人用，空 = 不限制 |
| `commandPrefix` | `/` | 命令前缀 |
| `sourceHint` | `true` | 在提问前加一行「来自哪个群、谁说的」，帮助模型消歧 |
| `turnTimeoutSec` | 1800 | 一轮任务最多等多久 |
| `interactionTimeoutSec` | 600 | 审批 / 提问最多等多久 |
| `maxInboundChars` | 8000 | 入站文本上限，超出截断 |
| `maxReplyChars` | 1500 | 回复切分上限 |
| `pollTimeoutSec` | 25 | 长轮询挂起秒数，应小于 `requestTimeoutMs` |
| `statePath` | `$DSH_HOME/integrations/dsh-astrbot/state.json` | 游标与会话绑定的落盘位置 |

---

## 在聊天里能用的命令

| 命令 | 作用 |
| --- | --- |
| `/new` | 开一个新会话（丢弃当前上下文） |
| `/stop` | 中断当前正在跑的一轮任务 |
| `/status` | 查看桥接状态、当前会话和队列 |
| `/help` | 帮助 |
| 其它任意文字 | 原样交给 DeepSeek Harness |

AstrBot 侧还有一个 `/dsh状态` 指令，用来在聊天里看队列水位。

### 审批与提问会转发到聊天里

DSH 执行到需要授权的工具时，聊天里会收到：

```
🔐 需要你授权
工具：pwsh
原因：执行 shell 命令

回复 1 = 允许一次，回复 2 = 拒绝
```

`ask_user_question` 的问题和选项也会逐条发到聊天里，回复序号即可；
没有选项时直接回复文字，会作为自定义答案回传。

---

## 安全

- **两把钥匙**：AstrBot 的 API Key（过挂载点）+ 插件的 HMAC 共享密钥（过插件这关）。
  HMAC 令牌为 `X-DSH-Bridge-Token: <exp>.<hmac_sha256(secret, exp)>`，默认 5 分钟有效，
  比较用常量时间函数，过期即拒。
- **强烈建议套 HTTPS 反代**：协议本身不带 TLS，明文传输意味着密钥和消息都裸露。
- 可以用 `allowUsers`（DSH 侧）和 `max_queue`（AstrBot 侧）做基本的风控。
- `/health` 接口**故意不做鉴权**（用于探活），只会泄露队列水位这类非敏感计数；
  其余接口一律要求令牌。

---

## 已验证

不是「写完就交」，下面这些是实际跑出来的：

```text
$ python tools/run_all_checks.py
AstrBot 侧插件自测 (python)     132/132 通过
DSH 侧插件测试 (node)           tests 36 / pass 36 / fail 0
打包契约检查 (node)             通过
真 cordis 加载冒烟 (node)       通过
全部检查通过
```

**跨语言一致性**：两端各有一份 HMAC 实现（Python 的 `hmac` / Node 的 `node:crypto`）。
测试里把同一个向量 `key="test-secret", msg="1700000000"` 硬编码在两边的用例里，
任何一端改了算法都会立刻红。

**DSH 侧是真 HTTP 端到端**：`tools/fake-astrbot.mjs` 起一个真的 HTTP 服务，
按同一份协议校验双钥匙、维护游标、长轮询挂起，DSH 插件用的是生产代码路径
（真的 `fetch`、真的 HMAC、真的状态落盘）。覆盖：新会话/复用会话、`/new`、
`/stop`、`/status`、未知命令当提问、白名单、事件去重、长回复切分、
Harness 报错回传、陈旧 `turn/end` 不误伤本轮、图片作为 image 内容块、鉴权失败。

**写测试时抓到并修掉的真实问题**：

1. **存盘失败被当成网络故障**。取件循环里 `store.save()` 抛错会被 catch 成「取件失败」，
   于是打退避、停摆好几秒——一个磁盘问题看起来像网络问题。已改为 `safeSave()`：
   存盘失败只记一条日志，不回退、不停摆。
   顺带发现 `rename` 在受限 ACL 下不可用，`writeJsonAtomic` 现在会退化成原地写。
2. **审批竞态**。原实现是「先发问题、再登记等待」，用户手快在登记生效前回复，
   那条回复会被当成新任务起一轮 Harness，原来的提问永远等不到答案。
   已改成 `beginInteraction()`：**先登记再提问**，用队列承载回复，
   重问、多问题、抢答都落在同一条通道里。
3. **`/reset` 的返回值被自己覆盖**。`{"dropped": cleared, **self._stats()}` 里
   `_stats()` 也有 `dropped`（累计丢弃数），展开在后面把它悄悄覆盖了，
   调用方看到的永远是累计值。已改名为 `cleared`，并加了断言守住。
4. **长轮询超时只认整数**。`int("0.3")` 抛错后回退到默认 25 秒。已改用 `_as_float`。
5. **`apply` 返回对象会让 cordis 当场把插件卸掉**（最严重的一个）。
   `lib/index.js` 的 `apply` 原来 `return bridge`。cordis 会把 apply 返回的对象当成一次性
   disposer，于是 fiber 在加载完成后**立刻被释放**，连带跑掉注册的 effect——表现为
   「插件装上了、/health 探活也发了，然后轮询当场停摆」，机器人完全不回消息。
   实测：返回任意对象（哪怕 `{notTheBridge:true}`）都会触发；不返回则一切正常。
   已改成什么都不返回，并加了一条断言守住。

> 第 5 条是这次最有价值的发现，而且**只有真 cordis 才暴露得出来**：此前 36 项假 ctx 测试
> 全绿，因为假 ctx 根本没有 fiber 这个概念。这也是为什么仓库里多了 `cordis-smoke.mjs`——
> 它把模块真的交给 `new Context()` + `ctx.plugin()`，验的是接线而不是逻辑。

**接口调用方式逐条对照过上游**（AstrBot `master`，v4.28）：
`Context.register_web_api(route, handler, methods, desc)` 的签名与
`/api/v1/plugins/extensions/<plugin>` 挂载点、`PluginMultiDict` **不是 dict 子类**、
`Context.send_message(umo, MessageChain)`、`filter.event_message_type(EventMessageType.ALL)`、
`AstrMessageEvent` 的 `unified_msg_origin` / `get_sender_id` / `is_private_chat`、
以及 `should_call_llm(True)` 实际语义是「禁止默认 LLM 请求」（名字看着是反的，代码为准）。
DSH 侧对照的是 `@deepseek-ai/dsh-api-session-controller` 的 `SessionController` 类型定义
（`create / prompt / cancel` 的入参形状）和 `dsh-session` 的 `SessionEventMap`
（`assistant/message` 的 `data.message.content` 取文本、`turn/start / turn/end` 的配对）。

---

## 已知边界

- **没有客户端设置界面**。DSH 插件目前只有主机端，配置写在 profile 的 cordis 配置里
  （`cordis.patch.yml` 已给出可抄的模板）。dsh-im 那种 WebUI 设置面板还没有做。
- **纯文本呈现**。回复是纯文本，没有卡片、按钮、消息编辑或流式刷新；
  长回复按段落切成多条。要做原生卡片得走渠道各自的接口，那正是 dsh-im 的领域。
- **会话绑定是「一个聊天窗口一个 Harness 会话」**。没有做话题 / 线程级别的细分。
- **群聊上下文**。默认只转发「私聊 + @机器人」，群里 @ 之前的历史模型看不到。
- **审批/提问是一次性的**。等不到答复会超时（默认 10 分钟），超时按取消 / 拒绝处理。
- **游标是内存队列 + 落盘游标**。AstrBot 重启会丢未取走的队列（不丢已处理的游标）；
  DSH 侧崩溃重放靠事件 id 去重（保留最近 200 个）。

---

## 开发

```bash
python tools/run_all_checks.py          # 一键跑完全部检查
python tools/test_astrbot_plugin.py     # 只跑 AstrBot 侧
node tools/dsh-plugin.test.mjs          # 只跑 DSH 侧
node tools/verify-package.mjs           # 打包契约（镜像 DSH 安装器的质量门禁）
node tools/cordis-smoke.mjs             # 用真 cordis 加载（没有 cordis 会跳过）
```

`cordis-smoke.mjs` 会去 `$DSH_HOME/profiles/*/node_modules` 和全局 npm 布局里找
`@deepseek-ai/cordis`；找不到就明确跳过，而不是假装通过。想指定位置就设 `DSH_CORDIS_PATH`。

> DSH 侧测试**不要**用 `node --test`：它会给每个文件 spawn 子进程并用管道收输出，
> 在某些受限环境里会直接 EPERM。直接 `node <file>` 就是在同一进程内跑测试，等价且更快。

## License

MIT
