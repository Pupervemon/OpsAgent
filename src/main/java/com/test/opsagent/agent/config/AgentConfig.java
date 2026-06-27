package com.test.opsagent.agent.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.test.opsagent.agent.interceptors.ToolErrorInterceptor;
import com.test.opsagent.agent.tool.DateTimeTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Value("${agent.system-prompt:You are a concise and practical assistant.}")
    private String baseSystemPrompt;

    // 1. 全局单例的记忆保存器
    @Bean
    public MemorySaver memorySaver() {
        return new MemorySaver();
    }

    // 2. 全局单例的 Agent 引擎
    // 注意：这里的 ChatModel 会由 Spring AI Alibaba Starter 根据 application.yml 自动装配，不用自己写了！
    // 旧的单 Agent Bean 已禁用；当前由 AgentRegistry 按场景统一创建和管理 Agent。
    // @Bean
    public ReactAgent reactAgent(ChatModel chatModel, MemorySaver saver, DateTimeTools dateTimeTools) {

        String systemPrompt = baseSystemPrompt +
                "\n\nUse the getCurrentDateTime tool when the user asks about current date, time, or timezone.";

        return ReactAgent.builder()
                .name("chat_agent")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .saver(saver) // 挂载记忆器
                .methodTools(dateTimeTools) // 注入你的工具
                .interceptors(new ToolErrorInterceptor()) // 注入工具调用异常拦截器
                .build();
    }
}
