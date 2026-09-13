/** 一个「真的会说 HTTP」的假 AstrBot，用来端到端验证 DSH 侧插件。
 *
 * 它复刻 astrbot_plugin_dsh_bridge 的协议细节：挂载路径、双钥匙鉴权
 * （API Key + HMAC 令牌）、游标语义、长轮询挂起、reply 回落。
 */

import { createHmac, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';

export const EXTENSION_PATH = '/api/v1/plugins/extensions/astrbot_plugin_dsh_bridge';

function sign(secret, exp) {
  return createHmac('sha256', secret).update(String(exp)).digest('hex');
}

export function createFakeAstrBot({ secret = 'test-secret', apiKey = 'abk_test', version = '1.0.0' } = {}) {
  const state = {
    cursor: 0,
    queue: [],
    replies: [],
    requests: [],
    rejected: [],
    waiters: new Set(),
  };

  function verify(req, url) {
    const token = String(req.headers['x-dsh-bridge-token'] ?? '');
    if (!token.includes('.')) return 'missing token';
    const [rawExp, sig] = token.split('.');
    const exp = Number(rawExp);
    if (!Number.isFinite(exp) || exp < Math.floor(Date.now() / 1000)) return 'expired token';
    if (sign(secret, exp) !== sig) return 'bad signature';
    if (apiKey) {
      const auth = String(req.headers.authorization ?? '');
      if (auth !== 'Bearer ' + apiKey) return 'bad api key';
    }
    return null;
  }

  function pending(since, limit) {
    return state.queue.filter((item) => item.cursor > since).slice(0, limit);
  }

  function wake() {
    for (const waiter of [...state.waiters]) waiter();
  }

  function push(event) {
    state.cursor += 1;
    const item = {
      ...event,
      cursor: state.cursor,
      id: event.id ?? 'evt-' + String(state.cursor) + '-' + randomUUID().slice(0, 8),
      kind: event.kind ?? 'message',
    };
    state.queue.push(item);
    wake();
    return item;
  }

  function readJson(req) {
    return new Promise((resolve) => {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (!text) return resolve({});
        try {
          resolve(JSON.parse(text));
        } catch {
          resolve({});
        }
      });
    });
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    state.requests.push({ method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams) });

    const send = (status, payload) => {
      const body = JSON.stringify(payload);
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(body);
    };

    if (!url.pathname.startsWith(EXTENSION_PATH)) {
      return send(404, { ok: false, message: 'not found' });
    }
    const route = url.pathname.slice(EXTENSION_PATH.length);

    // 假插件也要求 API Key —— 真实 AstrBot 的挂载点强制 plugin scope。
    if (apiKey && String(req.headers.authorization ?? '') !== 'Bearer ' + apiKey) {
      return send(401, { ok: false, message: 'Invalid API key' });
    }

    const failure = verify(req, url);
    if (failure) {
      state.rejected.push(failure);
      return send(401, { ok: false, message: failure });
    }

    if (route === '/health' && req.method === 'GET') {
      return send(200, {
        ok: true,
        plugin: 'astrbot_plugin_dsh_bridge',
        version,
        cursor: state.cursor,
        queued: state.queue.length,
      });
    }

    if (route === '/inbox' && req.method === 'GET') {
      const since = Number(url.searchParams.get('cursor') ?? 0) || 0;
      const limit = Number(url.searchParams.get('limit') ?? 20) || 20;
      const timeout = Number(url.searchParams.get('timeout') ?? 0) || 0;
      const deadline = Date.now() + timeout * 1000;

      let events = pending(since, limit);
      while (events.length === 0 && Date.now() < deadline) {
        const remaining = deadline - Date.now();
        await new Promise((resolve) => {
          const timer = setTimeout(resolve, Math.max(5, Math.min(remaining, 25)));
          state.waiters.add(() => {
            clearTimeout(timer);
            resolve();
          });
        });
        events = pending(since, limit);
      }

      const next = events.length > 0 ? events[events.length - 1].cursor : since;
      // 和真插件一样：取走就裁掉，避免假服务器无限长胖
      state.queue = state.queue.filter((item) => item.cursor > next);
      return send(200, {
        ok: true,
        cursor: next,
        head: state.cursor,
        events,
        more: events.length >= limit,
      });
    }

    if (route === '/reply' && req.method === 'POST') {
      const payload = await readJson(req);
      if (!payload.umo || typeof payload.text !== 'string' || !payload.text.trim()) {
        return send(400, { ok: false, message: 'umo / text 不合法' });
      }
      state.replies.push({ umo: payload.umo, text: payload.text, at: Date.now() });
      wake();
      return send(200, { ok: true, sent: 1, chunks: 1 });
    }

    if (route === '/reset' && req.method === 'POST') {
      const dropped = state.queue.length;
      state.queue = [];
      return send(200, { ok: true, dropped });
    }

    return send(404, { ok: false, message: 'unknown route ' + route });
  });

  return {
    state,
    push,
    replies: state.replies,
    requests: state.requests,
    rejected: state.rejected,
    get cursor() {
      return state.cursor;
    },
    async listen() {
      await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
      const { port } = server.address();
      return 'http://127.0.0.1:' + String(port);
    },
    async close() {
      wake();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

/** 等待某个条件成立，用于替代脆弱的固定 sleep。 */
export async function waitFor(predicate, { timeoutMs = 5000, intervalMs = 15, label = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) throw new Error('等待超时：' + label);
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}
