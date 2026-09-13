/** 把 Harness 的 ctx.sessionController 包成插件内部用的窄接口。
 *
 * 这样做的意义：编排逻辑（bridge.js）只依赖这 3 个方法，测试里换成一个假的
 * 端口就能把「取件 → 起会话 → 提问 → 收结果 → 回发」整条链路跑完，不需要真的
 * 起一个 DSH。
 */

/** 方法签名来自 @deepseek-ai/dsh-api-session-controller 的 SessionController。 */
export function createSessionControllerPort(sessionController) {
  if (!sessionController || typeof sessionController.prompt !== 'function') {
    throw new TypeError('需要 Harness 的 sessionController 服务');
  }
  return {
    async create({ cwd, agentPreset } = {}) {
      const request = {};
      if (cwd) request.cwd = cwd;
      if (agentPreset) request.agentPreset = agentPreset;
      const value = await sessionController.create(request);
      return { sessionId: value?.sessionId, agentPreset: value?.agentPreset };
    },

    async prompt({ sessionId, requestId, content, signal }) {
      const parts = Array.isArray(content) ? content : [];
      if (parts.length === 0) throw new TypeError('prompt 内容不能为空');
      return sessionController.prompt(
        { requestId, sessionId, mode: 'queue', content: parts },
        signal,
      );
    },

    async cancel({ sessionId }) {
      return sessionController.cancel({ sessionId });
    },
  };
}
