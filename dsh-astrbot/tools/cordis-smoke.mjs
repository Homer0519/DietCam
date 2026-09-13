/** 用「真的 cordis 运行时」加载本插件，验证它确实是个合法的 DSH 插件。
 *
 * 和 dsh-plugin.test.mjs 的区别：那边用手写的假 ctx 验插件自己的逻辑；
 * 这边把模块交给真的 @deepseek-ai/cordis，验的是**接线**：
 *   - 模块形状能被 cordis 接受；
 *   - inject: ['sessionController'] 真的会拦住加载，服务就位后才 apply；
 *   - apply 注册的事件、启动的轮询真的跑在 cordis 生命周期里；
 *   - 释放之后轮询确实停了。
 *
 * cordis 不在本仓库依赖里（按 DSH 打包规矩不能声明官方包），所以靠探测去找；
 * 找不到就明确跳过，而不是假装通过。
 *
 * 跑法：node tools/cordis-smoke.mjs
 */

import { existsSync } from 'node:fs';
import { mkdir, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { createFakeAstrBot, waitFor } from './fake-astrbot.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');

// --------------------------------------------------------------- 找 cordis

function candidatePaths() {
  const list = [];
  if (process.env.DSH_CORDIS_PATH) list.push(process.env.DSH_CORDIS_PATH);
  const homes = [process.env.DSH_HOME, process.env.USERPROFILE ? join(process.env.USERPROFILE, '.dsh') : null].filter(Boolean);
  for (const home of homes) {
    for (const profile of ['web', 'default']) {
      list.push(join(home, 'profiles', profile, 'node_modules', '@deepseek-ai', 'cordis'));
    }
  }
  const prefixes = [process.env.APPDATA ? join(process.env.APPDATA, 'npm') : null, '/usr/local/lib', '/usr/lib'].filter(Boolean);
  for (const prefix of prefixes) {
    list.push(join(prefix, 'node_modules', '@deepseek-ai', 'dsh', 'node_modules', '@deepseek-ai', 'cordis'));
    list.push(join(prefix, 'node_modules', '@deepseek-ai', 'cordis'));
  }
  return list;
}

async function loadCordis() {
  try {
    return await import('@deepseek-ai/cordis');
  } catch {
    // 本仓库按 DSH 规矩不声明官方包，解析不到很正常
  }
  for (const candidate of candidatePaths()) {
    if (existsSync(join(candidate, 'package.json'))) {
      return import(pathToFileURL(join(candidate, 'lib', 'index.js')).href);
    }
  }
  return null;
}

const cordis = await loadCordis();
if (!cordis) {
  console.log('跳过：环境里找不到 @deepseek-ai/cordis。');
  console.log('想跑这项检查，请把 DSH_CORDIS_PATH 指向 cordis 包目录。');
  process.exit(0);
}
const { Context } = cordis;
const plugin = await import(pathToFileURL(join(ROOT, 'dsh-plugin', 'lib', 'index.js')).href);

// ------------------------------------------------------------ 断言与诊断

const problems = [];
const steps = [];

function record(ok, message) {
  steps.push((ok ? '  ok   ' : '  FAIL ') + message);
  if (!ok) problems.push(message);
}

/** 等待条件成立；超时就带上现场信息，别让人猜。 */
async function expect(label, predicate, { timeoutMs = 5000, diagnose } = {}) {
  try {
    await waitFor(predicate, { timeoutMs, label });
    record(true, label);
    return true;
  } catch {
    let extra = '';
    try {
      const detail = diagnose ? diagnose() : '';
      extra = detail ? '｜现场：' + detail : '';
    } catch {
      extra = '';
    }
    record(false, label + '（超时）' + extra);
    return false;
  }
}

const TMP = join(ROOT, 'tools', '.test_tmp', 'cordis-smoke');
await rm(TMP, { recursive: true, force: true });
await mkdir(TMP, { recursive: true });

const server = createFakeAstrBot({ secret: 'smoke-secret', apiKey: 'abk_smoke' });
const url = await server.listen();

const prompts = [];
const sessionController = {
  async create() { return { sessionId: 'sess-smoke' }; },
  async prompt(request) { prompts.push(request); return { accepted: true }; },
  async cancel() { return { accepted: true }; },
};

const requestTail = () => {
  const tail = server.requests.slice(-4).map((r) => r.path.split('/').pop() + '?' + JSON.stringify(r.query));
  return '请求数 ' + server.requests.length + '，最近 ' + tail.join(' , ');
};

const ctx = new Context();

// 先加载插件但**不给 sessionController**：inject 应该拦住它
const fiber = ctx.plugin(plugin, {
  baseUrl: url,
  apiKey: 'abk_smoke',
  secret: 'smoke-secret',
  statePath: join(TMP, 'state.json'),
  pollTimeoutSec: 0,
  idleDelayMs: 10,
  requestTimeoutMs: 3000,
  turnTimeoutSec: 30,
});

await new Promise((r) => setTimeout(r, 400));
record(server.requests.length === 0, 'inject 未满足时插件不启动（apply 没跑）');

// 补上服务，插件这时才该被激活
ctx.provide('sessionController', sessionController);

await expect('服务就位后 cordis 激活插件（收到 /health 探测）',
  () => server.requests.some((r) => r.path.endsWith('/health')),
  { diagnose: requestTail });

await expect('轮询循环跑起来了（持续收到 /inbox）',
  () => server.requests.filter((r) => r.path.endsWith('/inbox')).length >= 1,
  { diagnose: requestTail });

// 入站消息 → Harness 提问
server.push({ umo: 'aiocqhttp:FriendMessage:smoke', text: '冒烟测试', sender_id: 'u1', is_private: true });

await expect('入站消息变成本地 Harness 提问',
  () => prompts.length >= 1,
  { timeoutMs: 6000, diagnose: () => requestTail() + '；prompts=' + prompts.length });

if (prompts.length >= 1) {
  record(prompts[0].sessionId === 'sess-smoke', 'prompt 发到了注入的 sessionController');
  record(
    typeof prompts[0].content?.[0]?.text === 'string' && prompts[0].content[0].text.includes('冒烟测试'),
    'prompt 内容正确',
  );
}

// 通过真的 cordis 事件总线发会话事件：验的是 ctx.on(..., {global:true}) 真的接上了
ctx.emit('session/event', { id: 'sess-smoke' }, { type: 'turn/start', data: { turn: 1 } });
ctx.emit('session/event', { id: 'sess-smoke' }, {
  type: 'assistant/message',
  data: { turn: 1, step: 1, message: { role: 'assistant', content: [{ type: 'text', text: '冒烟通过' }] } },
});
ctx.emit('session/event', { id: 'sess-smoke' }, { type: 'turn/end', data: { turn: 1, reason: 'success' } });

await expect('经 cordis 事件总线收齐正文并回发',
  () => server.replies.length >= 1,
  { timeoutMs: 6000, diagnose: () => 'replies=' + JSON.stringify(server.replies.map((r) => r.text)) });

if (server.replies.length >= 1) {
  record(server.replies[0].text === '冒烟通过', '回发正文是「' + server.replies[0].text + '」');
}

// 审批 waterfall 是否也接上了
if (typeof ctx.waterfall === 'function') {
  const approval = ctx.waterfall(
    'approval/request',
    { agent: { session: { id: 'sess-smoke' } }, toolName: 'pwsh' },
    () => Promise.resolve('next'),
  );
  const asked = await expect('审批请求经 cordis waterfall 转到聊天里',
    () => server.replies.length >= 2,
    { timeoutMs: 6000, diagnose: () => 'replies=' + server.replies.length });
  if (asked) {
    record(server.replies[1].text.includes('需要你授权'), '审批提示文案正确');
    server.push({ umo: 'aiocqhttp:FriendMessage:smoke', text: '1', sender_id: 'u1', is_private: true });
    const outcome = await approval;
    record(outcome === 'allowed-once', '聊天里回复 1 得到 allowed-once（实际 ' + outcome + '）');
  }
} else {
  steps.push('  skip 这个 cordis 版本没有 ctx.waterfall，跳过审批 waterfall 验证');
}

// 释放后轮询必须停下来
const before = server.requests.length;
if (typeof fiber?.dispose === 'function') await fiber.dispose();
else await fiber;
await new Promise((r) => setTimeout(r, 400));
record(
  server.requests.length - before <= 1,
  '释放后轮询停止（释放后新增 ' + (server.requests.length - before) + ' 个请求）',
);

await server.close();
await rm(TMP, { recursive: true, force: true });

console.log(steps.join('\n'));
if (problems.length === 0) {
  console.log('\ncordis 冒烟测试通过（真 cordis ' + (cordis.version ?? '') + ' 加载本插件）');
  process.exit(0);
}
console.log('\ncordis 冒烟测试失败：' + problems.length + ' 项');
process.exit(1);
