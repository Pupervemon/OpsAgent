package com.test.opsagent.agent.runtime;

import java.util.Set;

/**
 * Agent 工具集抽象。
 * <p>
 * 每个业务场景声明自己支持哪些 AgentScene，以及需要注入哪些 @Tool 对象。
 * 这样可以避免把所有工具都塞进一个 ReactAgent，降低误调用和权限扩散风险。
 */
public interface AgentToolSet {

    /**
     * 当前工具集适用的场景。
     */
    Set<AgentScene> scenes();

    /**
     * 返回 Spring AI / Alibaba Graph 可识别的 @Tool 宿主对象。
     */
    Object[] methodTools();

    /**
     * 工具集自己的使用约束，会拼接到对应 Agent 的 system prompt 中。
     */
    default String prompt() {
        return "";
    }

    /**
     * 判断当前工具集是否应该注入指定场景。
     */
    default boolean supports(AgentScene scene) {
        return scenes().contains(scene);
    }
}
