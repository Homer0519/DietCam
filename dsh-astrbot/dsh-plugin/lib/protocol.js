/** 与 AstrBot 侧 astrbot_plugin_dsh_bridge 的 HTTP 协议客户端。 */

import { createHmac, randomUUID } from 'node:crypto';

export const EXTENSION_PATH = '/api/v1/plugins/extensions/astrbot_plugin_dsh_bridge';

/** 允许用户只填站点根地址，自动补上插件挂载路径；已经写全了就不动。 */
export function resolveBaseUrl(raw) {
  const trimmed = String(raw ?? '').trim().replace(/\/+$/, '');
  if (!trimmed) throw new Error('baseUrl 不能为空');
  if (trimmed.endsWith(EXTENSION_PATH)) return trimmed;
  return trimmed + EXTENSION_PATH;
}

/** X-DSH-Bridge-Token 的值："<exp>.<hex hmac_sha256(secret, exp)>"。 */
export function bridgeToken(secret, expSec) {
  const digest = createHmac('sha256', String(secret)).update(String(expSec)).digest('hex');
  return `${expSec}.${digest}`;
}

/** 把调用方的 signal 和超时合成一个 signal（Node 18 没有 AbortSignal.any）。 */
export function withTimeout(signal, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort(new Error(`请求超过 ${timeoutMs}ms 未返回`));
  }, timeoutMs);
  if (timer.unref) timer.unref();

  const onAbort = () => controller.abort(signal?.reason);
  if (signal) {
    if (signal.aborted) onAbort();
    else signal.addEventListener('abort', onAbort, { once: true });
  }
  return {
    signal: controller.signal,
    dispose() {
      clearTimeout(timer);
      if (signal) signal.removeEventListener?.('abort', onAbort);
    },
  };
}

export class BridgeError extends Error {
  constructor(message, { status, body } = {}) {
    super(message);
    this.name = 'BridgeError';
    this.status = status;
    this.body = body;
  }
}

export class BridgeClient {
  constructor({
    baseUrl,
    apiKey = '',
    secret,
    fetchImpl,
    requestTimeoutMs = 40000,
    clock = () => Date.now(),
    tokenTtlSec = 240,
    newId = () => randomUUID(),
  } = {}) {
    this.endpoint = resolveBaseUrl(baseUrl);
    this.apiKey = String(apiKey ?? '');
    this.secret = String(secret ?? '');
    this.fetch = fetchImpl ?? globalThis.fetch;
    if (typeof this.fetch !== 'function') {
      throw new TypeError('当前运行环境没有 fetch，请用 Node 18.17+ 或注入 fetchImpl');
    }
    this.requestTimeoutMs = requestTimeoutMs;
    this.clock = clock;
    this.tokenTtlSec = Math.max(30, Math.trunc(tokenTtlSec));
    this.newId = newId;
    this.cachedToken = null;
    this.cachedTokenExp = 0;
  }

  token() {
    const now = Math.floor(this.clock() / 1000);
    if (this.cachedToken && now < this.cachedTokenExp) return this.cachedToken;
    const exp = now + this.tokenTtlSec;
    this.cachedToken = bridgeToken(this.secret, exp);
    // 留 30 秒余量，避免请求正好卡在过期边界上
    this.cachedTokenExp = exp - 30;
    return this.cachedToken;
  }

  headers(extra = {}) {
    const headers = {
      accept: 'application/json',
      'x-dsh-bridge-token': this.token(),
      'user-agent': 'dsh-astrbot/1.0.0',
      ...extra,
    };
    if (this.apiKey) headers.authorization = `Bearer ${this.apiKey}`;
    return headers;
  }

  async call(method, path, { query, body, signal } = {}) {
    const url = new URL(this.endpoint + path);
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value === undefined || value === null) continue;
      url.searchParams.set(key, String(value));
    }
    const guard = withTimeout(signal, this.requestTimeoutMs);
    try {
      const response = await this.fetch(url, {
        method,
        headers: this.headers(body === undefined ? {} : { 'content-type': 'application/json' }),
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: guard.signal,
      });
      const text = await response.text();
      let parsed = null;
      if (text) {
        try {
          parsed = JSON.parse(text);
        } catch {
          parsed = { raw: text };
        }
      }
      if (!response.ok) {
        const detail = parsed?.message ?? parsed?.raw ?? response.statusText;
        throw new BridgeError(`AstrBot 返回 ${response.status}: ${detail}`, {
          status: response.status,
          body: parsed,
        });
      }
      return parsed ?? {};
    } finally {
      guard.dispose();
    }
  }

  health(signal) {
    return this.call('GET', '/health', { signal });
  }

  /** 长轮询取件。timeoutSec 交给服务端挂起，客户端超时另有 requestTimeoutMs 兜底。 */
  inbox({ cursor = 0, timeoutSec = 25, limit = 20, signal } = {}) {
    return this.call('GET', '/inbox', {
      query: { cursor, timeout: timeoutSec, limit },
      signal,
    });
  }

  reply({ umo, text, signal } = {}) {
    return this.call('POST', '/reply', { body: { umo, text }, signal });
  }

  reset(signal) {
    return this.call('POST', '/reset', { body: {}, signal });
  }

  whoami(signal) {
    return this.call('GET', '/whoami', { signal });
  }
}
