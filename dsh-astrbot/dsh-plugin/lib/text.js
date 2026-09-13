/** 从 Harness 会话事件里取文本，以及把长回复切成适合聊天窗口的多条。 */

/**
 * 取 assistant/message 事件里的正文。
 *
 * 事件形状（见 @deepseek-ai/dsh-session 的 SessionEventMap）：
 *   { type: 'assistant/message', data: { turn, step, message: { role, content: ContentBlock[] } } }
 * content 里既有 text 块也有 tool-call 块，这里只要 text。
 */
export function extractAssistantText(event) {
  if (!event || event.type !== 'assistant/message') return '';
  const content = event.data?.message?.content;
  if (!Array.isArray(content)) return '';
  const parts = [];
  for (const block of content) {
    if (!block || typeof block !== 'object') continue;
    if (typeof block.text !== 'string') continue;
    if (block.type !== undefined && block.type !== 'text') continue;
    parts.push(block.text);
  }
  return parts.join('').trim();
}

const BREAKS = ['\n\n', '\n', '。', '！', '？', '；', '. ', '! ', '? ', ' '];

/** 在自然边界上把文本切成不超过 limit 的若干段。切不动就硬切。 */
export function splitForChat(text, limit) {
  const trimmed = String(text ?? '').trim();
  if (!trimmed) return [];
  const size = Number.isFinite(limit) && limit > 0 ? Math.trunc(limit) : 0;
  if (size === 0 || trimmed.length <= size) return [trimmed];

  const chunks = [];
  let rest = trimmed;
  while (rest.length > size) {
    const window = rest.slice(0, size);
    let cut = -1;
    for (const sep of BREAKS) {
      const at = window.lastIndexOf(sep);
      if (at < 0) continue;
      const candidate = at + (sep === '\n\n' ? 0 : sep.length);
      if (candidate > cut) cut = candidate;
    }
    if (cut <= 0 || cut < Math.floor(size / 3)) cut = size;
    chunks.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trimStart();
  }
  if (rest) chunks.push(rest);
  return chunks.filter(Boolean);
}

/** 入站文本截断，避免有人把整本书贴进来撑爆上下文。 */
export function clampInbound(text, limit) {
  const trimmed = String(text ?? '').trim();
  if (!Number.isFinite(limit) || limit <= 0 || trimmed.length <= limit) return trimmed;
  return `${trimmed.slice(0, limit)}\n\n…（消息过长，已截断 ${trimmed.length - limit} 字）`;
}
