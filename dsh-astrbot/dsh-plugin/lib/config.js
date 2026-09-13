/** 插件配置：默认值 + 归一化 + 可读的问题清单。 */

export const DEFAULTS = Object.freeze({
  enabled: true,
  baseUrl: '',
  apiKey: '',
  secret: '',
  // 单次长轮询挂起多久（秒）。AstrBot 侧还会用自己的 max_poll_seconds 夹一层。
  pollTimeoutSec: 25,
  // 两轮当中间隔（毫秒）；取完一批立刻再取，避免空转又不会打爆服务端。
  idleDelayMs: 250,
  // 取件失败后的退避：起点、上限。
  errorBackoffMs: 3000,
  maxBackoffMs: 60000,
  // 单次 HTTP 请求超时（毫秒）。应大于 pollTimeoutSec。
  requestTimeoutMs: 40000,
  // Harness 会话
  workspace: '',
  agentPreset: '',
  // 一轮任务最多等多久（秒）
  turnTimeoutSec: 1800,
  // 人机交互（审批 / 提问）最多等多久（秒）
  interactionTimeoutSec: 600,
  // 准入
  allowUsers: [],
  // 命令前缀
  commandPrefix: '/',
  // 在用户文本前加一行来源信息（哪个群、谁说的）
  sourceHint: true,
  // 入站文本上限，超出截断，防止有人贴一本书进来
  maxInboundChars: 8000,
  // 回复切分上限，和 AstrBot 侧的 max_message_chars 保持一致比较自然
  maxReplyChars: 1500,
  // 单张图片体积上限
  maxImageBytes: 8 * 1024 * 1024,
  // 状态文件路径；留空用 $DSH_HOME/integrations/dsh-astrbot/state.json
  statePath: '',
});

function asInt(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : fallback;
}

function asBool(value, fallback) {
  if (typeof value === 'boolean') return value;
  if (value === undefined || value === null || value === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).trim().toLowerCase());
}

function asString(value, fallback = '') {
  return value === undefined || value === null ? fallback : String(value);
}

function asStringList(value) {
  if (Array.isArray(value)) return value.map((item) => String(item).trim()).filter(Boolean);
  if (typeof value === 'string') {
    return value.split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean);
  }
  return [];
}

/**
 * 把用户配置归一化成插件内部使用的形状。
 * 返回 problems 而不是抛错，让插件能把问题一次说清楚再决定要不要继续跑。
 */
export function normalizeConfig(raw = {}) {
  const source = raw && typeof raw === 'object' ? raw : {};
  const config = {
    enabled: asBool(source.enabled, DEFAULTS.enabled),
    baseUrl: asString(source.baseUrl, DEFAULTS.baseUrl).trim(),
    apiKey: asString(source.apiKey, DEFAULTS.apiKey).trim(),
    secret: asString(source.secret, DEFAULTS.secret),
    pollTimeoutSec: Math.max(0, asInt(source.pollTimeoutSec, DEFAULTS.pollTimeoutSec)),
    idleDelayMs: Math.max(0, asInt(source.idleDelayMs, DEFAULTS.idleDelayMs)),
    errorBackoffMs: Math.max(500, asInt(source.errorBackoffMs, DEFAULTS.errorBackoffMs)),
    maxBackoffMs: Math.max(1000, asInt(source.maxBackoffMs, DEFAULTS.maxBackoffMs)),
    requestTimeoutMs: Math.max(1000, asInt(source.requestTimeoutMs, DEFAULTS.requestTimeoutMs)),
    workspace: asString(source.workspace, DEFAULTS.workspace).trim(),
    agentPreset: asString(source.agentPreset, DEFAULTS.agentPreset).trim(),
    turnTimeoutSec: Math.max(10, asInt(source.turnTimeoutSec, DEFAULTS.turnTimeoutSec)),
    interactionTimeoutSec: Math.max(10, asInt(source.interactionTimeoutSec, DEFAULTS.interactionTimeoutSec)),
    allowUsers: asStringList(source.allowUsers),
    commandPrefix: asString(source.commandPrefix, DEFAULTS.commandPrefix) || DEFAULTS.commandPrefix,
    sourceHint: asBool(source.sourceHint, DEFAULTS.sourceHint),
    maxInboundChars: Math.max(200, asInt(source.maxInboundChars, DEFAULTS.maxInboundChars)),
    maxReplyChars: Math.max(100, asInt(source.maxReplyChars, DEFAULTS.maxReplyChars)),
    maxImageBytes: Math.max(0, asInt(source.maxImageBytes, DEFAULTS.maxImageBytes)),
    statePath: asString(source.statePath, DEFAULTS.statePath).trim(),
  };

  const problems = [];
  if (config.enabled && !config.baseUrl) problems.push('baseUrl 未配置：不知道该连哪台 AstrBot');
  if (config.enabled && !config.secret) problems.push('secret 未配置：必须和 AstrBot 插件里的 hmac_secret 一致');
  if (config.requestTimeoutMs <= config.pollTimeoutSec * 1000) {
    problems.push(
      `requestTimeoutMs (${config.requestTimeoutMs}ms) 应该大于 pollTimeoutSec (${config.pollTimeoutSec}s)，`
      + '否则长轮询会被客户端自己掐断',
    );
  }
  return { config, problems };
}
