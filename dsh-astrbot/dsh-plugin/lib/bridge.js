/** AstrBotBridge：取件 → 起/复用 Harness 会话 → 提问 → 收结果 → 回发。
 *
 * 这一层刻意不直接依赖 cordis：所有外部能力（HTTP 客户端、状态存储、Harness
 * 会话端口）都是构造参数，因此可以用假实现把整条链路测完。
 */

import { randomUUID } from 'node:crypto';

import { renderApproval, parseApproval, renderQuestion, parseQuestionAnswer } from './interactions.js';
import { clampInbound, extractAssistantText, splitForChat } from './text.js';

export const HELP_TEXT = [
  'DSH × AstrBot 桥接命令：',
  '/new    开一个新会话（丢弃当前上下文）',
  '/stop   中断当前正在跑的一轮任务',
  '/status 查看桥接状态、当前会话和队列',
  '/help   显示这条帮助',
  '',
  '其它内容会原样交给 DeepSeek Harness 处理，结果回到这个会话。',
].join('\n');

function sleep(ms, signal) {
  if (!(ms > 0)) return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(done, ms);
    if (timer.unref) timer.unref();
    function done() {
      signal?.removeEventListener?.('abort', done);
      resolve();
    }
    signal?.addEventListener?.('abort', done, { once: true });
  });
}

export class AstrBotBridge {
  constructor({ client, store, port, config, logger = console, clock = () => Date.now(), newId = () => randomUUID() }) {
    if (!client) throw new TypeError('AstrBotBridge 需要 client');
    if (!store) throw new TypeError('AstrBotBridge 需要 store');
    if (!port) throw new TypeError('AstrBotBridge 需要 port');
    if (!config) throw new TypeError('AstrBotBridge 需要 config');

    this.client = client;
    this.store = store;
    this.port = port;
    this.config = config;
    this.logger = logger;
    this.clock = clock;
    this.newId = newId;

    this.running = false;
    this.abort = null;
    this.loopPromise = null;

    // sessionId -> 正在等待的一轮
    this.activeTurns = new Map();
    // sessionId -> umo，供审批/提问回传定位聊天窗口
    this.sessionToUmo = new Map();
    // umo -> 等待用户回答的交互
    this.pendingInteractions = new Map();
    // umo -> 串行化队列，保证同一个人不会并发跑两轮
    this.turnChains = new Map();

    this.stats = { received: 0, replied: 0, failed: 0, skipped: 0, commands: 0, turns: 0 };
  }

  // ------------------------------------------------------------- 生命周期

  start() {
    if (this.running) return;
    this.running = true;
    this.abort = new AbortController();
    this.loopPromise = this.pollLoop().catch((error) => {
      this.logger.error?.('[dsh-astrbot] 取件循环意外退出: ' + (error?.stack ?? error));
    });
  }

  async stop() {
    if (!this.running) return;
    this.running = false;
    this.abort?.abort(new Error('插件已停止'));
    for (const pending of [...this.pendingInteractions.values()]) pending.close();
    this.pendingInteractions.clear();
    await Promise.resolve(this.loopPromise).catch(() => {});
    this.loopPromise = null;
    await this.safeSave();
  }

  /**
   * 存盘失败不能影响收发。
   *
   * 之前这里是直接把 store.save() 抛出的错误扔给取件循环，结果一个磁盘问题会被
   * 当成网络故障处理：不但打出误导性的日志，还会触发退避、让取件停摆好几秒。
   * 游标下次再存就是了，最坏情况只是重启后重放一批事件。
   */
  async safeSave() {
    try {
      await this.store.save();
      return true;
    } catch (error) {
      this.logger.warn?.('[dsh-astrbot] 状态存盘失败（不影响收发）：' + (error?.message ?? error));
      return false;
    }
  }

  // --------------------------------------------------------------- 取件

  async pollLoop() {
    let backoff = 0;
    while (this.running) {
      const signal = this.abort.signal;
      try {
        const result = await this.client.inbox({
          cursor: this.store.cursor,
          timeoutSec: this.config.pollTimeoutSec,
          limit: 20,
          signal,
        });
        backoff = 0;

        const events = Array.isArray(result?.events) ? result.events : [];
        // 先处理再推进游标：中途崩溃最多重放，不会丢消息。
        for (const event of events) await this.dispatch(event);
        this.store.setCursor(Number(result?.cursor ?? this.store.cursor));
        await this.safeSave();

        if (events.length === 0) await sleep(this.config.idleDelayMs, signal);
      } catch (error) {
        if (!this.running) break;
        backoff = backoff === 0
          ? this.config.errorBackoffMs
          : Math.min(backoff * 2, this.config.maxBackoffMs);
        this.logger.warn?.(
          '[dsh-astrbot] 取件失败，' + Math.round(backoff / 1000) + 's 后重试：' + (error?.message ?? error),
        );
        await sleep(backoff, this.abort.signal);
      }
    }
  }

  async dispatch(event) {
    if (!event || event.kind !== 'message') return;
    const umo = String(event.umo ?? '');
    if (!umo) return;

    if (event.id && this.store.hasSeen(event.id)) {
      this.stats.skipped += 1;
      return;
    }
    if (event.id) {
      this.store.markSeen(event.id);
      this.store.dirty = true;
    }
    this.stats.received += 1;

    if (!this.allowed(event)) {
      this.stats.skipped += 1;
      this.logger.info?.('[dsh-astrbot] 忽略未授权用户 sender_id=' + String(event.sender_id ?? '') + ' umo=' + umo);
      return;
    }

    // 正在等这个人的回答（审批/提问）——这条消息就是答案，别当新任务。
    const pending = this.pendingInteractions.get(umo);
    if (pending) {
      pending.deliver(event);
      return;
    }

    const text = String(event.text ?? '').trim();
    const images = Array.isArray(event.images) ? event.images : [];
    if (!text && images.length === 0) return;

    const prefix = this.config.commandPrefix;
    if (prefix && text.startsWith(prefix)) {
      const body = text.slice(prefix.length).trim();
      const pieces = body.split(/\s+/);
      const rawCommand = pieces.shift() ?? '';
      const handled = await this.runCommand(umo, rawCommand.toLowerCase(), pieces.join(' '));
      if (handled) {
        this.stats.commands += 1;
        return;
      }
    }

    this.enqueueTurn(umo, () => this.runTurn(umo, event));
  }

  allowed(event) {
    const allow = this.config.allowUsers;
    if (!Array.isArray(allow) || allow.length === 0) return true;
    return allow.includes(String(event.sender_id ?? '').trim());
  }

  enqueueTurn(umo, fn) {
    const previous = this.turnChains.get(umo) ?? Promise.resolve();
    const run = previous
      .catch(() => {})
      .then(fn)
      .catch((error) => {
        this.logger.error?.('[dsh-astrbot] 处理 ' + umo + ' 出错: ' + (error?.stack ?? error));
      });
    this.turnChains.set(umo, run);
    run.then(() => {
      if (this.turnChains.get(umo) === run) this.turnChains.delete(umo);
    });
    return run;
  }

  // --------------------------------------------------------------- 命令

  async runCommand(umo, command, args) {
    switch (command) {
      case 'new':
      case 'reset':
      case '新会话': {
        const binding = this.store.bindingFor(umo);
        if (binding) this.sessionToUmo.delete(binding.sessionId);
        this.store.clearBinding(umo);
        await this.safeSave();
        await this.reply(umo, '🆕 已开启新会话，下一条消息会开一个全新的 Harness 会话。');
        return true;
      }
      case 'stop':
      case '停止':
      case '中断': {
        const binding = this.store.bindingFor(umo);
        if (!binding) {
          await this.reply(umo, '当前没有绑定会话。');
          return true;
        }
        try {
          await this.port.cancel({ sessionId: binding.sessionId });
          await this.reply(umo, '⏹ 已请求中断当前任务。');
        } catch (error) {
          await this.reply(umo, '中断失败：' + (error?.message ?? error));
        }
        return true;
      }
      case 'status':
      case '状态': {
        await this.reply(umo, this.statusText());
        return true;
      }
      case 'help':
      case '帮助': {
        await this.reply(umo, HELP_TEXT);
        return true;
      }
      case 'ping': {
        await this.reply(umo, 'pong');
        return true;
      }
      default:
        // 不认识的命令当成普通提问交给 Harness
        return false;
    }
  }

  statusText() {
    const lines = [
      '📊 DSH × AstrBot 桥接',
      '取件游标：' + this.store.cursor + '　待跑队列：' + this.turnChains.size,
      '累计：收 ' + this.stats.received + ' / 回 ' + this.stats.replied
        + ' / 失败 ' + this.stats.failed + ' / 跳过 ' + this.stats.skipped,
      '活跃任务：' + this.activeTurns.size,
    ];
    const bindings = this.store.listBindings();
    if (bindings.length > 0) {
      lines.push('已绑定会话（' + bindings.length + '）：');
      for (const binding of bindings.slice(0, 10)) {
        lines.push('　' + binding.umo + ' → ' + binding.sessionId);
      }
    }
    return lines.join('\n');
  }

  // --------------------------------------------------------------- 一轮

  async ensureSession(umo) {
    const existing = this.store.bindingFor(umo);
    if (existing) {
      this.sessionToUmo.set(existing.sessionId, umo);
      return existing;
    }
    try {
      const created = await this.port.create({
        cwd: this.config.workspace || undefined,
        agentPreset: this.config.agentPreset || undefined,
      });
      const sessionId = created?.sessionId;
      if (!sessionId || typeof sessionId !== 'string') {
        throw new Error('sessionController.create 没有返回 sessionId');
      }
      this.store.setBinding(umo, sessionId);
      await this.safeSave();
      this.sessionToUmo.set(sessionId, umo);
      this.logger.info?.('[dsh-astrbot] 为 ' + umo + ' 新建 Harness 会话 ' + sessionId);
      return this.store.bindingFor(umo);
    } catch (error) {
      this.logger.error?.('[dsh-astrbot] 创建 Harness 会话失败: ' + (error?.stack ?? error));
      return null;
    }
  }

  sourceHint(event) {
    const platform = event.platform_name || event.platform || 'astrbot';
    const where = event.is_private ? '私聊' : (event.group_id ? '群 ' + event.group_id : '群聊');
    const who = event.sender_name || event.sender_id || '未知用户';
    return '[AstrBot · ' + platform + ' · ' + where + ' · ' + who + ']';
  }

  /** 把一条 AstrBot 事件转成 Harness prompt 的内容块。 */
  buildContent(event) {
    const parts = [];
    const notes = Array.isArray(event.notes) ? event.notes.filter(Boolean) : [];
    if (notes.length > 0) parts.push({ type: 'text', text: '（附件提示：' + notes.join('；') + '）' });

    const raw = clampInbound(event.text, this.config.maxInboundChars);
    const text = this.config.sourceHint && raw ? this.sourceHint(event) + '\n' + raw : raw;
    if (text) parts.push({ type: 'text', text });

    const maxBytes = this.config.maxImageBytes;
    for (const image of Array.isArray(event.images) ? event.images : []) {
      const data = typeof image?.base64 === 'string' ? image.base64 : '';
      if (!data) continue;
      // base64 长度约为原始字节的 4/3
      if (maxBytes > 0 && data.length * 0.75 > maxBytes) {
        this.logger.info?.('[dsh-astrbot] 跳过一张超过体积上限的图片');
        continue;
      }
      parts.push({
        type: 'image',
        mediaType: typeof image.mediaType === 'string' && image.mediaType ? image.mediaType : 'image/jpeg',
        data,
      });
    }
    return parts;
  }

  async runTurn(umo, event) {
    const binding = await this.ensureSession(umo);
    if (!binding) {
      this.stats.failed += 1;
      await this.reply(umo, '❌ 没能创建 Harness 会话，请查看 DSH 侧的日志。');
      return;
    }

    const content = this.buildContent(event);
    if (content.length === 0) return;

    const sessionId = binding.sessionId;
    const requestId = this.newId();
    const turn = {
      umo,
      requestId,
      turnNumber: undefined,
      parts: [],
      startedAt: this.clock(),
      finish: null,
      timer: null,
    };
    const completion = new Promise((resolve) => {
      turn.finish = resolve;
    });

    this.activeTurns.set(sessionId, turn);
    this.sessionToUmo.set(sessionId, umo);
    this.stats.turns += 1;
    turn.timer = setTimeout(() => this.finishTurn(sessionId, 'timeout'), this.config.turnTimeoutSec * 1000);
    if (turn.timer.unref) turn.timer.unref();

    let outcome;
    try {
      await this.port.prompt({ sessionId, requestId, content, signal: this.abort?.signal });
      outcome = await completion;
    } catch (error) {
      this.finishTurn(sessionId, 'prompt-error', String(error?.message ?? error));
      outcome = { reason: 'prompt-error', detail: String(error?.message ?? error), text: '' };
    }

    const text = String(outcome?.text ?? '').trim();
    if (outcome?.reason === 'success') {
      await this.reply(umo, text || '（Harness 这一轮没有产出文本）');
      return;
    }
    if (outcome?.reason === 'timeout') {
      await this.reply(
        umo,
        '⏱ 这轮任务超过 ' + this.config.turnTimeoutSec + 's 还没结束，Harness 里还在继续跑。\n'
        + '可以用 /status 查看，或用 /stop 中断。',
      );
      return;
    }
    this.stats.failed += 1;
    const detail = outcome?.detail ? '\n（' + outcome.detail + '）' : '';
    const tail = text ? '\n\n' + text : '';
    await this.reply(umo, '⚠️ 这一轮没有正常结束（' + (outcome?.reason ?? 'unknown') + '）。' + detail + tail);
  }

  finishTurn(sessionId, reason, detail) {
    const turn = this.activeTurns.get(sessionId);
    if (!turn) return;
    this.activeTurns.delete(sessionId);
    if (turn.timer) clearTimeout(turn.timer);
    turn.finish({ reason, detail, text: turn.parts.join('\n\n') });
  }

  /** 接 Harness 的 session/event：累积正文，turn 结束时收尾。 */
  handleSessionEvent(session, event) {
    const sessionId = session?.id;
    if (typeof sessionId !== 'string' || !event) return;
    const turn = this.activeTurns.get(sessionId);
    if (!turn) return;

    if (event.type === 'turn/start') {
      if (turn.turnNumber === undefined) turn.turnNumber = event.data?.turn;
      return;
    }
    if (event.type === 'assistant/message') {
      const text = extractAssistantText(event);
      if (text) turn.parts.push(text);
      return;
    }
    if (event.type === 'turn/end') {
      const ending = event.data?.turn;
      // 认得出自己的 turn 就只认自己的，避免把上一轮的结束当成本轮结束。
      if (turn.turnNumber !== undefined && ending !== turn.turnNumber) return;
      const reason = event.data?.reason;
      this.finishTurn(sessionId, reason === 'success' ? 'success' : String(reason ?? 'ended'));
    }
  }

  // --------------------------------------------------------- 审批 / 提问

  /**
   * 开一条「问答通道」：**先登记再提问**。
   *
   * 这样做是为了关掉一个真实的竞态：如果先发消息再登记，用户手快在登记生效
   * 之前就回了，那条回复会被当成新任务去起 Harness 轮次，原来的提问永远等不到
   * 答案。通道用队列实现，所以重问、多问题、抢答都能正确落到同一个通道里。
   */
  beginInteraction(umo, signal) {
    const entry = { queue: [], waiter: null, closed: false, deliver: null, close: null };

    entry.deliver = (value) => {
      if (entry.closed) return;
      if (entry.waiter) {
        const settle = entry.waiter;
        entry.waiter = null;
        settle(value);
      } else {
        entry.queue.push(value);
      }
    };

    const onAbort = () => entry.close();
    entry.close = () => {
      if (entry.closed) return;
      entry.closed = true;
      signal?.removeEventListener?.('abort', onAbort);
      if (this.pendingInteractions.get(umo) === entry) this.pendingInteractions.delete(umo);
      if (entry.waiter) {
        const settle = entry.waiter;
        entry.waiter = null;
        settle(null);
      }
    };

    if (signal?.aborted) {
      // 已经取消：登记都不必了，next() 会立刻拿到 null
      entry.closed = true;
    } else {
      signal?.addEventListener?.('abort', onAbort, { once: true });
      this.pendingInteractions.set(umo, entry);
    }

    const timeoutMs = this.config.interactionTimeoutSec * 1000;
    return {
      next() {
        if (entry.queue.length > 0) return Promise.resolve(entry.queue.shift());
        if (entry.closed) return Promise.resolve(null);
        return new Promise((resolve) => {
          const timer = setTimeout(() => {
            if (entry.waiter === settle) entry.waiter = null;
            resolve(null);
          }, timeoutMs);
          if (timer.unref) timer.unref();
          const settle = (value) => {
            clearTimeout(timer);
            resolve(value);
          };
          entry.waiter = settle;
        });
      },
      close: () => entry.close(),
    };
  }

  umoForSession(agent) {
    const sessionId = agent?.session?.id ?? agent?.id;
    if (typeof sessionId !== 'string') return undefined;
    return this.sessionToUmo.get(sessionId);
  }

  /**
   * 权限审批：转发到聊天里等答复。
   * 返回值必须是 Harness 认可的三种结果之一。
   */
  async handleApproval(request, next) {
    const umo = this.umoForSession(request?.agent);
    if (!umo) return next();

    const interaction = this.beginInteraction(umo, request?.signal);
    try {
      let decision = null;
      for (let attempt = 0; attempt < 2 && decision === null; attempt += 1) {
        if (attempt > 0) await this.reply(umo, '没看懂，请回复 1（允许一次）或 2（拒绝）。');
        await this.reply(umo, renderApproval({ toolName: request?.toolName, reason: request?.reason }));
        const answer = await interaction.next();
        if (!answer) return 'cancelled';
        decision = parseApproval(answer.text);
      }
      return decision ?? 'rejected';
    } finally {
      interaction.close();
    }
  }

  /** ask_user_question：逐个问题转发，等用户回答后组装成 Harness 要的形状。 */
  async handleQuestion(request, next) {
    const umo = this.umoForSession(request?.agent);
    if (!umo) return next();

    const questions = Array.isArray(request?.questions) ? request.questions : [];
    if (questions.length === 0) return next();

    const interaction = this.beginInteraction(umo, request?.signal);
    try {
      const answers = [];
      for (let index = 0; index < questions.length; index += 1) {
        const question = questions[index];
        let parsed = null;
        for (let attempt = 0; attempt < 3 && !parsed; attempt += 1) {
          if (attempt > 0) await this.reply(umo, '没看懂你的选择，请回复选项序号（或直接回复自定义内容）。');
          await this.reply(umo, renderQuestion(question, index, questions.length));
          const answer = await interaction.next();
          if (!answer) {
            const error = new Error('用户没有回答（已取消或超时）');
            error.name = 'UserQuestionError';
            error.code = 'ASK_CANCELLED';
            throw error;
          }
          parsed = parseQuestionAnswer(answer.text, question);
        }
        if (!parsed) {
          const error = new Error('反复无法解析用户的回答');
          error.name = 'UserQuestionError';
          error.code = 'ASK_ABORTED';
          throw error;
        }
        answers.push({
          id: question?.id,
          selected: parsed.selected,
          ...(parsed.custom === undefined ? {} : { custom: parsed.custom }),
        });
      }
      return { answers };
    } finally {
      interaction.close();
    }
  }

  // --------------------------------------------------------------- 回发

  async reply(umo, text) {
    const chunks = splitForChat(text, this.config.maxReplyChars);
    if (chunks.length === 0) return false;
    let ok = true;
    for (const chunk of chunks) {
      try {
        const result = await this.client.reply({ umo, text: chunk });
        if (result && result.ok === false) {
          ok = false;
          this.stats.failed += 1;
          this.logger.warn?.('[dsh-astrbot] AstrBot 拒收回发: ' + (result.error ?? '未知原因'));
          break;
        }
        this.stats.replied += 1;
      } catch (error) {
        ok = false;
        this.stats.failed += 1;
        this.logger.error?.('[dsh-astrbot] 回发失败: ' + (error?.message ?? error));
        break;
      }
    }
    return ok;
  }
}
