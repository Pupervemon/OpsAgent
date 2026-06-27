package com.test.opsagent.agent.runtime;

import java.util.Locale;

/**
 * Agent 场景枚举。
 * <p>
 * 后续新增固定运维流程、知识库问答、Supervisor Agent 时，优先在这里扩展场景，
 * 服务层只负责按场景路由，不直接关心具体工具集合。
 */
public enum AgentScene {

    /**
     * 运维自由查询场景：只允许查询、诊断、解释，不允许改变服务器状态。
     */
    OPS_QUERY;

    /**
     * 将前端传入的 agentType/scene 转成内部场景。
     * 未传或传入未知值时默认回退到 OPS_QUERY，保持旧前端请求兼容。
     */
    public static AgentScene from(String value) {
        if (value == null || value.isBlank()) {
            return OPS_QUERY;
        }

        String normalized = value.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);

        for (AgentScene scene : values()) {
            if (scene.name().equals(normalized)) {
                return scene;
            }
        }

        return OPS_QUERY;
    }
}
