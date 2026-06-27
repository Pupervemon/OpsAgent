package com.test.opsagent.agent.runtime;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.test.opsagent.agent.interceptors.ToolErrorInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 注册中心。
 * <p>
 * 负责按场景创建并保存 ReactAgent 实例。服务层只通过场景获取 Agent，
 * 不直接知道某个 Agent 注入了哪些工具。
 */
@Component
public class AgentRegistry {

    private final Map<AgentScene, ReactAgent> agents = new EnumMap<>(AgentScene.class);

    /**
     * 应用启动时构建所有当前支持的 Agent。
     */
    public AgentRegistry(ChatModel chatModel, MemorySaver saver, List<AgentToolSet> toolSets,
            @Value("${agent.system-prompt:You are a concise and practical assistant.}") String baseSystemPrompt) {
        agents.put(AgentScene.OPS_QUERY, buildOpsQueryAgent(chatModel, saver, toolSets, baseSystemPrompt));
    }

    /**
     * 根据场景获取 Agent，未知场景回退到 OPS_QUERY。
     */
    public ReactAgent get(AgentScene scene) {
        return agents.getOrDefault(scene, agents.get(AgentScene.OPS_QUERY));
    }

    /**
     * 构建运维自由查询 Agent。
     * <p>
     * 该 Agent 只接收查询类工具，system prompt 明确限制它不能执行重启、删除、
     * 修改配置、部署、回滚等会改变服务器状态的操作。
     */
    private ReactAgent buildOpsQueryAgent(ChatModel chatModel, MemorySaver saver, List<AgentToolSet> toolSets,
            String baseSystemPrompt) {
        List<AgentToolSet> selectedToolSets = toolSets.stream()
                .filter(toolSet -> toolSet.supports(AgentScene.OPS_QUERY))
                .toList();

        Object[] methodTools = selectedToolSets.stream()
                .flatMap(toolSet -> Arrays.stream(toolSet.methodTools()))
                .toArray();

        String toolPrompts = selectedToolSets.stream()
                .map(AgentToolSet::prompt)
                .filter(prompt -> prompt != null && !prompt.isBlank())
                .reduce("", (left, right) -> left + "\n\n" + right);

        String systemPrompt = baseSystemPrompt + """

                You are a read-only operations query assistant.
                You can inspect server status, allowed service status, health endpoints, logs,
                application version information, current time, and operations knowledge.
                You must not restart services, delete files, modify configuration, deploy, roll back,
                run write SQL, or make any change to the server.
                If a requested tool or runtime dependency is unavailable, say so plainly and use the
                available evidence.
                Base your conclusion on tool results. When evidence is insufficient, say what is missing.
                Answer with: conclusion, evidence, possible causes, and suggested next steps.
                """ + toolPrompts;

        return ReactAgent.builder()
                .name("ops_query_agent")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .saver(saver)
                .methodTools(methodTools)
                .interceptors(new ToolErrorInterceptor())
                .build();
    }
}
