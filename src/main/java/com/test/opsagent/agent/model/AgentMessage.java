package com.test.opsagent.agent.model;

/**
 * 会话历史消息。
 * role 使用 user/assistant，content 是对应消息文本。
 */
public record AgentMessage(String role, String content) {
}
