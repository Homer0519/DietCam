/** 状态持久化：取件游标、会话绑定、最近处理过的事件 id。 */

import { mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

const VERSION = 1;
const MAX_RECENT_IDS = 200;

export function defaultStateDir() {
  const home = process.env.DSH_HOME || join(homedir(), '.dsh');
  return join(home, 'integrations', 'dsh-astrbot');
}

export function defaultStatePath() {
  return join(defaultStateDir(), 'state.json');
}

/** 读 JSON；文件不存在返回 fallback。 */
export async function readJson(file, fallback) {
  try {
    return JSON.parse(await readFile(file, 'utf8'));
  } catch (error) {
    if (error?.code === 'ENOENT') return fallback;
    throw error;
  }
}

/** 先写临时文件再 rename，避免掉电时留下半个 JSON。
 *
 * 有些环境不允许 rename（受限 ACL、跨设备目录、某些沙箱），这时退化成原地写。
 * 原地写会丢掉「写入原子性」这一个好处，但总比状态完全存不下去要好。
 */
export async function writeJsonAtomic(file, value) {
  await mkdir(dirname(file), { recursive: true });
  const payload = `${JSON.stringify(value, null, 2)}\n`;
  const temp = `${file}.${process.pid}.${Date.now()}.tmp`;
  await writeFile(temp, payload, 'utf8');
  try {
    await rename(temp, file);
  } catch {
    await writeFile(file, payload, 'utf8');
    try {
      await unlink(temp);
    } catch {
      // 临时文件清理失败无关紧要
    }
  }
}

export class StateStore {
  constructor(path) {
    this.path = path;
    this.state = { version: VERSION, cursor: 0, bindings: {}, recentIds: [] };
    this.dirty = false;
  }

  async load() {
    let raw;
    try {
      raw = await readJson(this.path, null);
    } catch (error) {
      // 状态文件损坏不该让机器人彻底起不来：备份一份再重来。
      const backup = `${this.path}.corrupt-${Date.now()}`;
      try {
        await rename(this.path, backup);
      } catch {
        // 备份失败就算了，下面照常从空状态开始
      }
      raw = null;
      this.corruptError = error;
    }
    if (raw && typeof raw === 'object') {
      this.state = {
        version: VERSION,
        cursor: Number.isFinite(raw.cursor) ? Math.max(0, Math.trunc(raw.cursor)) : 0,
        bindings: raw.bindings && typeof raw.bindings === 'object' ? { ...raw.bindings } : {},
        recentIds: Array.isArray(raw.recentIds) ? raw.recentIds.filter((id) => typeof id === 'string') : [],
      };
    }
    return this;
  }

  get cursor() {
    return this.state.cursor;
  }

  setCursor(cursor) {
    const next = Number.isFinite(cursor) ? Math.max(0, Math.trunc(cursor)) : this.state.cursor;
    if (next !== this.state.cursor) {
      this.state.cursor = next;
      this.dirty = true;
    }
  }

  bindingFor(umo) {
    const entry = this.state.bindings[umo];
    return entry && typeof entry.sessionId === 'string' ? entry : null;
  }

  setBinding(umo, sessionId, extra = {}) {
    this.state.bindings[umo] = { sessionId, updatedAt: Date.now(), ...extra };
    this.dirty = true;
  }

  clearBinding(umo) {
    if (umo in this.state.bindings) {
      delete this.state.bindings[umo];
      this.dirty = true;
      return true;
    }
    return false;
  }

  listBindings() {
    return Object.entries(this.state.bindings).map(([umo, entry]) => ({ umo, ...entry }));
  }

  hasSeen(id) {
    return typeof id === 'string' && this.state.recentIds.includes(id);
  }

  markSeen(id) {
    if (typeof id !== 'string' || !id) return;
    if (this.state.recentIds.includes(id)) return;
    this.state.recentIds.push(id);
    if (this.state.recentIds.length > MAX_RECENT_IDS) {
      this.state.recentIds.splice(0, this.state.recentIds.length - MAX_RECENT_IDS);
    }
    this.dirty = true;
  }

  async save() {
    if (!this.dirty) return false;
    await writeJsonAtomic(this.path, this.state);
    this.dirty = false;
    return true;
  }
}
