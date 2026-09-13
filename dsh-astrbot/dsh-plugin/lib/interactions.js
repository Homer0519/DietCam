/** 审批与提问在纯文字聊天里的呈现和解析。 */

const ALLOW_WORDS = new Set([
  '1', 'y', 'yes', 'ok', 'allow', 'allowed', 'approve', 'approved',
  '允许', '允许一次', '同意', '可以', '好', '行', '是',
]);
const DENY_WORDS = new Set([
  '2', 'n', 'no', 'reject', 'rejected', 'deny', 'denied', 'cancel',
  '拒绝', '不许', '不行', '取消', '否',
]);

export function renderApproval({ toolName, reason } = {}) {
  const lines = ['🔐 需要你授权', `工具：${toolName || '未知工具'}`];
  if (reason) lines.push(`原因：${reason}`);
  lines.push('', '回复 1 = 允许一次，回复 2 = 拒绝');
  return lines.join('\n');
}

/** 解析审批答复；认不出来返回 null（调用方负责再问一次）。 */
export function parseApproval(text) {
  const normalized = String(text ?? '').trim().toLowerCase();
  if (!normalized) return null;
  if (ALLOW_WORDS.has(normalized)) return 'allowed-once';
  if (DENY_WORDS.has(normalized)) return 'rejected';
  return null;
}

export function renderQuestion(question, index = 0, total = 1) {
  const lines = [];
  const header = String(question?.header ?? '').trim();
  const body = String(question?.question ?? '').trim();
  lines.push(total > 1 ? `❓ 问题 ${index + 1}/${total}` : '❓ 需要你确认');
  if (header) lines.push(`【${header}】`);
  if (body) lines.push(body);

  const options = Array.isArray(question?.options) ? question.options : [];
  if (options.length) {
    lines.push('');
    options.forEach((option, i) => {
      const description = option?.description ? ` — ${option.description}` : '';
      lines.push(`${i + 1}. ${option?.label ?? '(无标签)'}${description}`);
    });
    lines.push('');
    lines.push(
      question?.multiSelect
        ? '可多选：回复序号，用逗号分隔（例如 1,3）。也可以直接回复自定义内容。'
        : '回复序号进行选择。也可以直接回复自定义内容。',
    );
  } else {
    lines.push('');
    lines.push('直接回复你的答案即可。');
  }
  return lines.join('\n');
}

/**
 * 把用户的一句话解析成 DSH 需要的答案对象。
 *
 * 返回形状必须满足 Harness 的校验：selected 里的标签必须真的来自 options；
 * 单选时 selected 与 custom 不能同时非空。返回 null 表示没看懂，需要重问。
 */
export function parseQuestionAnswer(text, question) {
  const raw = String(text ?? '').trim();
  if (!raw) return null;

  const options = Array.isArray(question?.options) ? question.options : [];
  const labels = options.map((option) => String(option?.label ?? ''));
  const multi = question?.multiSelect === true;

  if (labels.length > 0) {
    const tokens = raw.split(/[,，、\s]+/).filter(Boolean);
    if (tokens.length > 0 && tokens.every((token) => /^\d+$/.test(token))) {
      const selected = [];
      for (const token of tokens) {
        const position = Number(token) - 1;
        if (!Number.isInteger(position) || position < 0 || position >= labels.length) {
          return null;
        }
        if (!selected.includes(labels[position])) selected.push(labels[position]);
      }
      if (!multi && selected.length > 1) return null;
      if (selected.length > 0) return { selected };
      return null;
    }
    const exact = labels.find((label) => label.toLowerCase() === raw.toLowerCase());
    if (exact) return { selected: [exact] };
  }

  // 落到这里就是自由文本答案
  return { selected: [], custom: raw };
}
