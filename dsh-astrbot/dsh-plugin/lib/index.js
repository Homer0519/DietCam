/** cordis 插件入口：把配置、HTTP 客户端、状态存储、Harness 会话端口接起来。
 *
 * 真正的业务编排都在 bridge.js，这里只负责 DSH 生命周期接线。
 */

import { normalizeConfig } from './config.js';
import { BridgeClient } from './protocol.js';
import { StateStore, defaultStatePath } from './store.js';
import { createSessionControllerPort } from './session-port.js';
import { AstrBotBridge } from './bridge.js';

export const name = 'dsh-astrbot';

// 没有 sessionController 就不启动：这个插件存在的意义就是驱动 Harness 会话。
export const inject = ['sessionController'];

function resolveLogger(ctx) {
  if (typeof ctx?.logger === 'function') return ctx.logger(name);
  return ctx?.logger ?? console;
}

export function createAstrBotPlugin(internals = {}) {
  return {
    name,
    inject,
    async apply(ctx, rawConfig = {}) {
      const logger = resolveLogger(ctx);
      const { config, problems } = normalizeConfig(rawConfig);

      if (config.enabled) {
        for (const problem of problems) logger.warn?.('[dsh-astrbot] 配置问题：' + problem);
      }
      if (!config.enabled) {
        logger.info?.('[dsh-astrbot] 配置里 enabled=false，跳过启动');
        return undefined;
      }
      if (problems.length > 0) {
        logger.error?.('[dsh-astrbot] 配置不完整，插件不会启动。请补齐 baseUrl / secret 后重载。');
        return undefined;
      }

      const MakeClient = internals.BridgeClient ?? BridgeClient;
      const MakeStore = internals.StateStore ?? StateStore;
      const MakeBridge = internals.AstrBotBridge ?? AstrBotBridge;
      const makePort = internals.createPort ?? createSessionControllerPort;

      const store = new MakeStore(config.statePath || defaultStatePath());
      await store.load();
      if (store.corruptError) {
        logger.warn?.('[dsh-astrbot] 状态文件损坏，已从空状态重新开始（原文件已备份）');
      }

      const client = new MakeClient({
        baseUrl: config.baseUrl,
        apiKey: config.apiKey,
        secret: config.secret,
        requestTimeoutMs: config.requestTimeoutMs,
      });

      // 先探一次活，把问题在日志里说清楚；连不上也照常启动，循环会自己重试。
      try {
        const health = await client.health();
        logger.info?.(
          '[dsh-astrbot] 已连上 AstrBot 桥接插件 v' + String(health?.version ?? '?')
          + '，待取事件 ' + String(health?.queued ?? 0) + ' 条',
        );
      } catch (error) {
        logger.error?.('[dsh-astrbot] 连不上 AstrBot：' + (error?.message ?? error) + '（仍在后台持续重试）');
      }

      const port = internals.port ?? makePort(ctx.sessionController);
      const bridge = new MakeBridge({ client, store, port, config, logger, ...internals.bridge });

      if (typeof ctx.on === 'function') {
        ctx.on('session/event', (session, event) => bridge.handleSessionEvent(session, event), { global: true });
        // prepend：抢在内置的 Web 客户端交互处理之前，把审批/提问引到聊天里。
        ctx.on('approval/request', (request, next) => bridge.handleApproval(request, next), {
          global: true,
          prepend: true,
        });
        ctx.on('user-questions/request', (request, next) => bridge.handleQuestion(request, next), {
          global: true,
          prepend: true,
        });
      }

      bridge.start();
      logger.info?.('[dsh-astrbot] 已启动，长轮询 ' + client.endpoint);
      internals.onBridge?.(bridge);

      if (typeof ctx.effect === 'function') {
        ctx.effect(() => () => {
          void bridge.stop();
        }, 'dsh-astrbot: bridge lifecycle');
      }

      // 这里**故意什么都不返回**。
      //
      // cordis 会把 apply 返回的对象当成一次性 disposer：只要返回一个对象（哪怕是
      // 无关的普通对象），fiber 就会在加载完成后立刻被释放，顺带跑掉上面注册的
      // effect —— 表现是「插件装上了、探活也发了，然后轮询当场停摆」。
      // 这个坑用假 ctx 测不出来，只有真的把模块交给 cordis 才会暴露。
      return undefined;
    },
  };
}

export async function apply(ctx, config = {}) {
  return createAstrBotPlugin().apply(ctx, config);
}
