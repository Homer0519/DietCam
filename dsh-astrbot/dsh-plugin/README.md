# dsh-astrbot（DSH 侧插件）

DeepSeek Harness 的 cordis 主机端插件：主动长轮询一台公网 AstrBot，
把聊天消息转成 Harness 会话的提问，再把结果发回原会话。

配套的 AstrBot 侧插件是 `../astrbot_plugin_dsh_bridge/`，协议见 `../docs/协议.md`。

## 结构

| 文件 | 职责 |
| --- | --- |
| `lib/index.js` | cordis 接线：`name` / `inject` / `apply`，注册事件与生命周期 |
| `lib/config.js` | 配置默认值与归一化，返回可读的问题清单 |
| `lib/protocol.js` | HTTP 客户端：baseUrl 补全、HMAC 令牌、超时合成 |
| `lib/store.js` | 状态落盘：游标、会话绑定、去重 id；原子写 + 降级 |
| `lib/session-port.js` | 把 `ctx.sessionController` 收窄成 3 个方法的窄接口 |
| `lib/interactions.js` | 审批 / 提问的渲染与答复解析（纯函数） |
| `lib/text.js` | 取 assistant 正文、长回复切分、入站截断 |
| `lib/bridge.js` | 编排：取件 → 起会话 → 提问 → 收结果 → 回发 |

## 为什么这么分层

`bridge.js` 只依赖四个注入进来的东西——HTTP 客户端、状态存储、Harness 会话端口、配置。
没有一样是 `import` 进来的具体实现。所以测试可以用一个「真的会说 HTTP 的假 AstrBot」
加上一个假的会话端口，把整条链路端到端跑一遍，而不需要起一个真的 DSH。

## 用到的 Harness 接口

`@deepseek-ai/dsh-api-session-controller` 提供的 `ctx.sessionController`：

```js
await sessionController.create({ cwd, agentPreset })        // -> { sessionId }
await sessionController.prompt(                             // -> { accepted: true }
  { requestId, sessionId, mode: 'queue', content: [{ type: 'text', text }] },
  signal,
)
await sessionController.cancel({ sessionId })               // -> { accepted: true }
```

以及三个事件：

```js
ctx.on('session/event', (session, event) => {}, { global: true })
ctx.on('approval/request', (request, next) => {}, { global: true, prepend: true })
ctx.on('user-questions/request', (request, next) => {}, { global: true, prepend: true })
```

会话事件里用到的是 `dsh-session` 的 `SessionEventMap` 形状：
`turn/start` / `turn/end` 配对，`assistant/message` 的
`data.message.content` 里筛 `type === 'text'` 的块拼成正文。

审批与提问的 hook 用 `prepend: true` 抢在内置的 Web 客户端处理之前，
这样聊天里的用户才是第一响应者；找不到对应的聊天窗口时 `return next()`，
把交互还给默认处理，不会把 Web 端卡死。

## 两个必须知道的契约

### 1. `apply` 不要返回对象

cordis 会把 `apply` 返回的对象当成一次性 disposer：只要返回一个对象
（哪怕是 `{ok:true}` 这种无关对象），fiber 就会在加载完成后**立刻被释放**，
连带跑掉 `ctx.effect` 注册的清理函数。

本插件踩过这个坑：原来的 `return bridge` 导致「插件装上了、/health 探活也发了，
然后轮询当场停摆」。现在 `apply` 什么都不返回；测试要拿 bridge 就用
`createAstrBotPlugin({ onBridge })`。

### 2. 不要声明 `@deepseek-ai/dsh-*` 依赖

DSH 运行时包用模块内的 Symbol 做标识，装出第二份物理副本会让模块标识分裂、
Host 查表失败。本包**一个依赖都没有**，只用 `node:` 内置模块。
`tools/verify-package.mjs` 会在仓库里先把安装器的质量门禁跑一遍：
未声明的裸 import、官方包依赖、patch 行解析，都在这里拦下。

## 测试

```bash
node ../../tools/dsh-plugin.test.mjs    # 逻辑 + 假 AstrBot 端到端，36 项
node ../../tools/verify-package.mjs     # 打包契约
node ../../tools/cordis-smoke.mjs       # 用真 cordis 加载，验接线
```

36 项里 20 项是对着真 HTTP 服务的端到端测试。
`cordis-smoke.mjs` 补的是假 ctx 测不到的那一层：inject 门禁、事件总线、
waterfall、以及释放行为。
