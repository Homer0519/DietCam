/** DSH 侧插件的测试：纯函数单测 + 对着「会说 HTTP 的假 AstrBot」做端到端。
 *
 * 运行：node --test tools/dsh-plugin.test.mjs   （在 dsh-astrbot/ 目录下）
 */

import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { AstrBotBridge } from '../dsh-plugin/lib/bridge.js';
import { normalizeConfig } from '../dsh-plugin/lib/config.js';
import { parseApproval, parseQuestionAnswer, renderQuestion } from '../dsh-plugin/lib/interactions.js';
import { createAstrBotPlugin } from '../dsh-plugin/lib/index.js';
import { BridgeClient, bridgeToken, resolveBaseUrl } from '../dsh-plugin/lib/protocol.js';
import { StateStore } from '../dsh-plugin/lib/store.js';
import { clampInbound, extractAssistantText, splitForChat } from '../dsh-plugin/lib/text.js';
import { createFakeAstrBot, waitFor } from './fake-astrbot.mjs';

// Node 与 Python 独立算出的同一个 HMAC —— 用来锁死两端的令牌算法一致。
const TOKEN_VECTOR = '1b74eaf87da46877ba651ac812b24a3bf01990e9ed0101b88268f60f965729e2';

const quietLogger = { info() {}, warn() {}, error() {}, debug() {} };

async function tempStatePath() {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-astrbot-test-'));
  return { path: join(dir, 'state.json'), dir };
}

function createHarness() {
  let counter = 0;
  const prompts = [];
  const cancelled = [];
  return {
    prompts,
    cancelled,
    port: {
      async create({ cwd, agentPreset } = {}) {
        counter += 1;
        return { sessionId: 'sess-' + String(counter), agentPreset, cwd };
      },
      async prompt({ sessionId, requestId, content }) {
        prompts.push({ sessionId, requestId, content });
        return { accepted: true };
      },
      async cancel({ sessionId }) {
        cancelled.push(sessionId);
        return { accepted: true };
      },
    },
  };
}

async function makeBridge(t, overrides = {}) {
  const server = createFakeAstrBot({ secret: 'test-secret', apiKey: 'abk_test' });
  const url = await server.listen();
  const { path, dir } = await tempStatePath();
  const store = new StateStore(path);
  await store.load();
  const harness = createHarness();

  const { config } = normalizeConfig({
    baseUrl: url,
    secret: 'test-secret',
    apiKey: 'abk_test',
    pollTimeoutSec: 0,
    idleDelayMs: 5,
    requestTimeoutMs: 3000,
    turnTimeoutSec: 60,
    interactionTimeoutSec: 5,
    maxReplyChars: 120,
    ...overrides,
  });

  const client = new BridgeClient({
    baseUrl: config.baseUrl,
    apiKey: config.apiKey,
    secret: config.secret,
    requestTimeoutMs: config.requestTimeoutMs,
  });

  const bridge = new AstrBotBridge({
    client, store, port: harness.port, config, logger: quietLogger,
  });

  t.after(async () => {
    await bridge.stop();
    await server.close();
    await rm(dir, { recursive: true, force: true });
  });

  return { bridge, store, server, harness, config, client };
}

function finishTurn(bridge, sessionId, text, { reason = 'success', turn = 1 } = {}) {
  bridge.handleSessionEvent({ id: sessionId }, { type: 'turn/start', data: { turn } });
  if (text !== undefined) {
    bridge.handleSessionEvent({ id: sessionId }, {
      type: 'assistant/message',
      data: { turn, step: 1, message: { role: 'assistant', content: [{ type: 'text', text }] } },
    });
  }
  bridge.handleSessionEvent({ id: sessionId }, { type: 'turn/end', data: { turn, reason } });
}

// ------------------------------------------------------------------ 纯函数

test('config: 缺 baseUrl / secret 会给出明确问题', () => {
  const { config, problems } = normalizeConfig({});
  assert.equal(config.enabled, true);
  assert.equal(problems.length, 2);
  assert.match(problems.join('\n'), /baseUrl/);
  assert.match(problems.join('\n'), /secret/);
});

test('config: allowUsers 支持数组和逗号分隔字符串', () => {
  assert.deepEqual(normalizeConfig({ allowUsers: ['1', ' 2 '] }).config.allowUsers, ['1', '2']);
  assert.deepEqual(normalizeConfig({ allowUsers: '1, 2，3' }).config.allowUsers, ['1', '2', '3']);
  assert.deepEqual(normalizeConfig({}).config.allowUsers, []);
});

test('config: 客户端超时必须大于长轮询时长', () => {
  const { problems } = normalizeConfig({ baseUrl: 'http://x', secret: 's', pollTimeoutSec: 30, requestTimeoutMs: 5000 });
  assert.match(problems.join('\n'), /requestTimeoutMs/);
});

test('text: 只从 assistant/message 的 text 块取正文', () => {
  assert.equal(extractAssistantText({ type: 'assistant/chunk' }), '');
  assert.equal(extractAssistantText({ type: 'assistant/message', data: {} }), '');
  const event = {
    type: 'assistant/message',
    data: {
      message: {
        content: [
          { type: 'text', text: '好的，' },
          { type: 'tool-call', callId: 'c1', name: 'pwsh' },
          { type: 'text', text: '已经处理完。' },
        ],
      },
    },
  };
  assert.equal(extractAssistantText(event), '好的，已经处理完。');
});

test('text: splitForChat 优先在段落/句子边界切分', () => {
  const text = '第一段内容。\n\n第二段内容。\n\n第三段内容。';
  const chunks = splitForChat(text, 12);
  assert.ok(chunks.length >= 2);
  for (const chunk of chunks) assert.ok(chunk.length <= 12, '分块超长: ' + chunk);
  assert.equal(chunks.join('').replace(/\s+/g, ''), text.replace(/\s+/g, ''));
  assert.equal(splitForChat('', 100).length, 0);
  assert.deepEqual(splitForChat('短', 100), ['短']);
});

test('text: splitForChat 遇到没有边界的长串会硬切且不丢字', () => {
  const text = 'x'.repeat(50);
  const chunks = splitForChat(text, 20);
  assert.equal(chunks.join(''), text);
  for (const chunk of chunks) assert.ok(chunk.length <= 20);
});

test('text: clampInbound 会截断并留痕', () => {
  const out = clampInbound('a'.repeat(100), 20);
  assert.ok(out.startsWith('a'.repeat(20)));
  assert.match(out, /已截断/);
  assert.equal(clampInbound('short', 20), 'short');
});

test('interactions: 审批答复解析', () => {
  assert.equal(parseApproval('1'), 'allowed-once');
  assert.equal(parseApproval(' 允许 '), 'allowed-once');
  assert.equal(parseApproval('2'), 'rejected');
  assert.equal(parseApproval('拒绝'), 'rejected');
  assert.equal(parseApproval('随便'), null);
});

test('interactions: 问题渲染包含序号与选项说明', () => {
  const text = renderQuestion({
    question: '选哪个方案？',
    header: '方案',
    options: [{ label: '甲', description: '快' }, { label: '乙' }],
  }, 0, 2);
  assert.match(text, /问题 1\/2/);
  assert.match(text, /1\. 甲 — 快/);
  assert.match(text, /2\. 乙/);
});

test('interactions: 序号选择 / 自定义文本 / 越界与多选规则', () => {
  const question = { options: [{ label: '甲' }, { label: '乙' }, { label: '丙' }] };
  assert.deepEqual(parseQuestionAnswer('2', question), { selected: ['乙'] });
  assert.deepEqual(parseQuestionAnswer('甲', question), { selected: ['甲'] });
  assert.deepEqual(parseQuestionAnswer('随便写点', question), { selected: [], custom: '随便写点' });
  // 单选给了两个序号 -> 不认识，让上层重问
  assert.equal(parseQuestionAnswer('1,2', question), null);
  // 越界序号不能当成自定义答案，否则会污染选项语义
  assert.equal(parseQuestionAnswer('9', question), null);

  const multi = { multiSelect: true, options: [{ label: '甲' }, { label: '乙' }, { label: '丙' }] };
  assert.deepEqual(parseQuestionAnswer('1,3', multi), { selected: ['甲', '丙'] });
  // 没有选项时任何文字都是自定义答案
  assert.deepEqual(parseQuestionAnswer('就这样', {}), { selected: [], custom: '就这样' });
});

test('protocol: baseUrl 自动补挂载路径且不重复补', () => {
  assert.equal(
    resolveBaseUrl('https://a.example.com/'),
    'https://a.example.com/api/v1/plugins/extensions/astrbot_plugin_dsh_bridge',
  );
  const full = resolveBaseUrl('https://a.example.com/api/v1/plugins/extensions/astrbot_plugin_dsh_bridge');
  assert.equal(resolveBaseUrl(full), full);
  assert.throws(() => resolveBaseUrl('   '));
});

test('protocol: 令牌与 Python 实现逐字节一致', () => {
  // 令牌格式是 "<exp>.<hex>"，hex 部分与 Python 侧 _sign() 的输出必须一致
  assert.equal(bridgeToken('test-secret', 1700000000), '1700000000.' + TOKEN_VECTOR);
});

test('store: 游标 / 绑定 / 去重 id 都能落盘再读回', async (t) => {
  const { path, dir } = await tempStatePath();
  t.after(() => rm(dir, { recursive: true, force: true }));

  const store = new StateStore(path);
  await store.load();
  store.setCursor(7);
  store.setBinding('aiocqhttp:FriendMessage:1', 'sess-a');
  store.markSeen('evt-1');
  await store.save();

  const reloaded = new StateStore(path);
  await reloaded.load();
  assert.equal(reloaded.cursor, 7);
  assert.equal(reloaded.bindingFor('aiocqhttp:FriendMessage:1').sessionId, 'sess-a');
  assert.equal(reloaded.hasSeen('evt-1'), true);
  assert.equal(reloaded.hasSeen('evt-2'), false);
  assert.equal(reloaded.clearBinding('aiocqhttp:FriendMessage:1'), true);
  assert.equal(reloaded.bindingFor('aiocqhttp:FriendMessage:1'), null);
});

test('store: 状态文件损坏时备份并从空状态启动', async (t) => {
  const { path, dir } = await tempStatePath();
  t.after(() => rm(dir, { recursive: true, force: true }));
  const { writeFile } = await import('node:fs/promises');
  await writeFile(path, '{ 这不是 json', 'utf8');

  const store = new StateStore(path);
  await store.load();
  assert.ok(store.corruptError);
  assert.equal(store.cursor, 0);
});

// ------------------------------------------------------------ 端到端（假 AstrBot）

test('e2e: 一条新消息会开新会话、提问、并把结果发回原会话', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'aiocqhttp:FriendMessage:u1', text: '帮我看下今天天气', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt 到达' });

  const prompt = harness.prompts[0];
  assert.equal(prompt.sessionId, 'sess-1');
  assert.equal(prompt.content[0].type, 'text');
  assert.match(prompt.content[0].text, /帮我看下今天天气/);
  // sourceHint 会把来源写进去，方便模型消歧
  assert.match(prompt.content[0].text, /AstrBot/);

  finishTurn(bridge, 'sess-1', '今天晴，25 度。');
  await waitFor(() => server.replies.length >= 1, { label: '回复送达' });
  assert.equal(server.replies[0].umo, 'aiocqhttp:FriendMessage:u1');
  assert.equal(server.replies[0].text, '今天晴，25 度。');
});

test('e2e: 同一个会话的第二条消息复用同一个 Harness 会话', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-a', text: '第一个问题', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: '第一个 prompt' });
  finishTurn(bridge, 'sess-1', '回答一');
  await waitFor(() => server.replies.length >= 1, { label: '第一条回复' });

  server.push({ umo: 'umo-a', text: '接着问', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 2, { label: '第二个 prompt' });
  assert.equal(harness.prompts[1].sessionId, 'sess-1');
});

test('e2e: /new 之后会换成新会话', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-b', text: '你好', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: '首个 prompt' });
  finishTurn(bridge, 'sess-1', '你好呀');
  await waitFor(() => server.replies.length >= 1, { label: '首个回复' });

  server.push({ umo: 'umo-b', text: '/new', sender_id: 'u1', is_private: true });
  await waitFor(() => server.replies.length >= 2, { label: '/new 回执' });
  assert.match(server.replies[1].text, /新会话/);

  server.push({ umo: 'umo-b', text: '重新开始', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 2, { label: '新会话 prompt' });
  assert.equal(harness.prompts[1].sessionId, 'sess-2');
});

test('e2e: /stop 会调用 Harness 取消，/status 回状态', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-c', text: '干活', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });
  finishTurn(bridge, 'sess-1', '好了');
  await waitFor(() => server.replies.length >= 1, { label: '回复' });

  server.push({ umo: 'umo-c', text: '/stop', sender_id: 'u1', is_private: true });
  await waitFor(() => server.replies.length >= 2, { label: '/stop 回执' });
  assert.deepEqual(harness.cancelled, ['sess-1']);

  server.push({ umo: 'umo-c', text: '/status', sender_id: 'u1', is_private: true });
  await waitFor(() => server.replies.length >= 3, { label: '/status 回执' });
  assert.match(server.replies[2].text, /桥接/);
});

test('e2e: 不认识的斜杠命令照常当提问发给 Harness', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();
  server.push({ umo: 'umo-d', text: '/不存在的命令 参数', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });
  assert.match(harness.prompts[0].content[0].text, /不存在的命令/);
});

test('e2e: allowUsers 白名单之外的发送者会被丢掉', async (t) => {
  const { bridge, server, harness } = await makeBridge(t, { allowUsers: ['vip'] });
  bridge.start();

  server.push({ umo: 'umo-e', text: '我是路人', sender_id: 'stranger', is_private: true });
  server.push({ umo: 'umo-e', text: '我是 VIP', sender_id: 'vip', is_private: true });

  await waitFor(() => harness.prompts.length >= 1, { label: '只有 VIP 触发' });
  assert.match(harness.prompts[0].content[0].text, /我是 VIP/);
  assert.equal(bridge.stats.skipped, 1);
});

test('e2e: 重复的事件 id 不会被处理两次', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  const event = { umo: 'umo-f', text: '只应处理一次', sender_id: 'u1', is_private: true, id: 'evt-fixed-1' };
  server.push(event);
  server.push(event);
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });
  finishTurn(bridge, 'sess-1', 'ok');
  await waitFor(() => server.replies.length >= 1, { label: '回复' });
  assert.equal(harness.prompts.length, 1);
});

test('e2e: 长回复按上限切成多条发送', async (t) => {
  const { bridge, server, harness, config } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-g', text: '给我一段长文', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  // 造一段明显超过上限、且带自然句读的回复
  const unit = '这是第若干句话，内容还挺长的。';
  const long = unit.repeat(Math.ceil((config.maxReplyChars * 2.5) / unit.length));
  assert.ok(long.length > config.maxReplyChars);
  finishTurn(bridge, 'sess-1', long);

  await waitFor(() => server.replies.length > 1, { label: '多段回复' });
  for (const reply of server.replies) {
    assert.ok(reply.text.length <= config.maxReplyChars, '分块超长: ' + String(reply.text.length));
  }
  assert.equal(server.replies.map((r) => r.text).join(''), long);
});

test('e2e: Harness 报错时把原因回给用户', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-h', text: '会失败的任务', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });
  finishTurn(bridge, 'sess-1', undefined, { reason: 'error' });

  await waitFor(() => server.replies.length >= 1, { label: '失败回复' });
  assert.match(server.replies[0].text, /没有正常结束/);
  assert.match(server.replies[0].text, /error/);
});

test('e2e: 上一轮的 turn/end 不会误伤本轮', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-i', text: '任务', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  bridge.handleSessionEvent({ id: 'sess-1' }, { type: 'turn/start', data: { turn: 5 } });
  // 陈旧事件：turn 号对不上
  bridge.handleSessionEvent({ id: 'sess-1' }, { type: 'turn/end', data: { turn: 4, reason: 'success' } });
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(server.replies.length, 0, '不该被陈旧 turn/end 提前收尾');

  bridge.handleSessionEvent({ id: 'sess-1' }, {
    type: 'assistant/message',
    data: { turn: 5, step: 1, message: { content: [{ type: 'text', text: '本轮结果' }] } },
  });
  bridge.handleSessionEvent({ id: 'sess-1' }, { type: 'turn/end', data: { turn: 5, reason: 'success' } });
  await waitFor(() => server.replies.length >= 1, { label: '本轮回复' });
  assert.equal(server.replies[0].text, '本轮结果');
});

test('e2e: 图片会作为 image 内容块传给 Harness', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  const base64 = Buffer.from('fake-jpeg-bytes').toString('base64');
  server.push({
    umo: 'umo-j',
    text: '看看这张图',
    sender_id: 'u1',
    is_private: true,
    images: [{ mediaType: 'image/jpeg', base64 }],
  });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });
  const content = harness.prompts[0].content;
  assert.equal(content.length, 2);
  assert.equal(content[1].type, 'image');
  assert.equal(content[1].mediaType, 'image/jpeg');
  assert.equal(content[1].data, base64);
});

test('e2e: 鉴权失败（密钥不对）时取件会失败并被记录', async (t) => {
  const server = createFakeAstrBot({ secret: 'the-real-secret', apiKey: 'abk_test' });
  const url = await server.listen();
  const { path, dir } = await tempStatePath();
  t.after(async () => { await server.close(); await rm(dir, { recursive: true, force: true }); });

  const client = new BridgeClient({ baseUrl: url, secret: 'wrong-secret', apiKey: 'abk_test', requestTimeoutMs: 2000 });
  await assert.rejects(() => client.health(), /401/);
  assert.ok(server.rejected.length >= 1);
  assert.equal(server.rejected[0], 'bad signature');
});

// ------------------------------------------------------------ 审批 / 提问

test('interaction: 审批回复 1 -> allowed-once', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-k', text: '跑个危险命令', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  const approval = bridge.handleApproval({ agent: { session: { id: 'sess-1' } }, toolName: 'pwsh', reason: '写文件' }, () => 'next');
  await waitFor(() => server.replies.length >= 1, { label: '审批提问' });
  assert.match(server.replies[0].text, /需要你授权/);
  assert.match(server.replies[0].text, /pwsh/);

  server.push({ umo: 'umo-k', text: '1', sender_id: 'u1', is_private: true });
  assert.equal(await approval, 'allowed-once');
});

test('interaction: 审批回复 2 -> rejected', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-l', text: '干活', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  const approval = bridge.handleApproval({ agent: { session: { id: 'sess-1' } }, toolName: 'fs' }, () => 'next');
  await waitFor(() => server.replies.length >= 1, { label: '审批提问' });
  server.push({ umo: 'umo-l', text: '拒绝', sender_id: 'u1', is_private: true });
  assert.equal(await approval, 'rejected');
});

test('interaction: 审批遇到看不懂的答复会重问，两次后默认拒绝', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-m', text: '干活', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  const approval = bridge.handleApproval({ agent: { session: { id: 'sess-1' } }, toolName: 'fs' }, () => 'next');
  await waitFor(() => server.replies.length >= 1, { label: '首次提问' });
  server.push({ umo: 'umo-m', text: '???', sender_id: 'u1', is_private: true });
  await waitFor(() => server.replies.length >= 2, { label: '重问' });
  server.push({ umo: 'umo-m', text: '还是乱答', sender_id: 'u1', is_private: true });
  assert.equal(await approval, 'rejected');
});

test('interaction: 没有绑定会话时不接管审批（交还给 next）', async (t) => {
  const { bridge } = await makeBridge(t);
  let calledNext = false;
  const result = await bridge.handleApproval({ agent: { session: { id: 'unknown' } } }, () => {
    calledNext = true;
    return 'from-next';
  });
  assert.equal(calledNext, true);
  assert.equal(result, 'from-next');
});

test('interaction: ask_user_question 单选与自定义答案', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-n', text: '开始', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  const pending = bridge.handleQuestion({
    agent: { session: { id: 'sess-1' } },
    questions: [{ id: 'q1', question: '选哪个？', options: [{ label: '甲' }, { label: '乙' }] }],
  }, () => 'next');

  await waitFor(() => server.replies.length >= 1, { label: '提问' });
  assert.match(server.replies[0].text, /选哪个？/);
  server.push({ umo: 'umo-n', text: '2', sender_id: 'u1', is_private: true });

  assert.deepEqual(await pending, { answers: [{ id: 'q1', selected: ['乙'] }] });
});

test('interaction: 多个问题会依次问，最后组装成 answers', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-o', text: '开始', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  const pending = bridge.handleQuestion({
    agent: { session: { id: 'sess-1' } },
    questions: [
      { id: 'q1', question: '第一个问题', options: [{ label: '甲' }, { label: '乙' }] },
      { id: 'q2', question: '第二个问题' },
    ],
  }, () => 'next');

  await waitFor(() => server.replies.length >= 1, { label: '问题一' });
  assert.match(server.replies[0].text, /问题 1\/2/);
  server.push({ umo: 'umo-o', text: '1', sender_id: 'u1', is_private: true });

  await waitFor(() => server.replies.length >= 2, { label: '问题二' });
  assert.match(server.replies[1].text, /问题 2\/2/);
  server.push({ umo: 'umo-o', text: '我自己写的答案', sender_id: 'u1', is_private: true });

  assert.deepEqual(await pending, {
    answers: [
      { id: 'q1', selected: ['甲'] },
      { id: 'q2', selected: [], custom: '我自己写的答案' },
    ],
  });
});

test('interaction: 交互等待期间的消息不会同时触发新一轮任务', async (t) => {
  const { bridge, server, harness } = await makeBridge(t);
  bridge.start();

  server.push({ umo: 'umo-p', text: '开始', sender_id: 'u1', is_private: true });
  await waitFor(() => harness.prompts.length >= 1, { label: 'prompt' });

  const pending = bridge.handleQuestion({
    agent: { session: { id: 'sess-1' } },
    questions: [{ id: 'q1', question: '继续吗？', options: [{ label: '是' }, { label: '否' }] }],
  }, () => 'next');
  await waitFor(() => server.replies.length >= 1, { label: '提问' });

  server.push({ umo: 'umo-p', text: '是', sender_id: 'u1', is_private: true });
  await pending;
  await new Promise((resolve) => setTimeout(resolve, 40));
  assert.equal(harness.prompts.length, 1, '回答交互不该被当成新提问');
});

// ------------------------------------------------------------ cordis 接线

test('cordis: apply 会注册事件、启动循环，并在 effect 清理时停止', async (t) => {
  const server = createFakeAstrBot({ secret: 's', apiKey: 'k' });
  const url = await server.listen();
  const { path, dir } = await tempStatePath();
  t.after(async () => { await server.close(); await rm(dir, { recursive: true, force: true }); });

  const handlers = new Map();
  const effects = [];
  const sessionController = {
    async create() { return { sessionId: 'sess-1' }; },
    async prompt() { return { accepted: true }; },
    async cancel() { return { accepted: true }; },
  };
  const ctx = {
    sessionController,
    logger: () => quietLogger,
    on(name, fn) { handlers.set(name, fn); },
    effect(fn) { effects.push(fn); },
  };

  let bridge = null;
  const plugin = createAstrBotPlugin({ onBridge: (created) => { bridge = created; } });
  assert.deepEqual(plugin.inject, ['sessionController']);
  const returned = await plugin.apply(ctx, {
    baseUrl: url,
    secret: 's',
    apiKey: 'k',
    statePath: path,
    pollTimeoutSec: 0,
    idleDelayMs: 5,
    requestTimeoutMs: 3000,
  });

  // apply 必须不返回值：返回对象会让 cordis 立刻释放 fiber（见 lib/index.js 的注释）
  assert.equal(returned, undefined, 'apply 不能返回对象，否则 cordis 会当场释放 fiber');
  assert.ok(bridge, 'onBridge 应该拿到创建好的 bridge');
  assert.deepEqual([...handlers.keys()].sort(), ['approval/request', 'session/event', 'user-questions/request']);
  assert.equal(effects.length, 1);

  // 清理函数应该把轮询停下来
  const disposers = effects.map((fn) => fn());
  await Promise.all(disposers.map((dispose) => dispose?.()));
  assert.equal(bridge.running, false);
});

test('cordis: 配置不全时直接拒绝启动并说明原因', async (t) => {
  const messages = [];
  const ctx = {
    sessionController: { async prompt() { return { accepted: true }; } },
    logger: () => ({ info() {}, warn(m) { messages.push(m); }, error(m) { messages.push(m); }, debug() {} }),
    on() {},
    effect() {},
  };
  const plugin = createAstrBotPlugin();
  const result = await plugin.apply(ctx, {});
  assert.equal(result, undefined);
  assert.match(messages.join('\n'), /配置不完整/);
});

test('cordis: enabled=false 时不启动也不报错', async () => {
  const ctx = {
    sessionController: { async prompt() { return { accepted: true }; } },
    logger: () => quietLogger,
    on() {},
    effect() {},
  };
  const result = await createAstrBotPlugin().apply(ctx, { enabled: false });
  assert.equal(result, undefined);
});
