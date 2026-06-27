package com.test.opsagent.agent.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 前端发送给后端的对话请求。
 * 兼容示例项目里的 Id/Question 命名，也兼容常见的小写字段，便于调试。
 */
@Getter
public class ChatRequest {

	/**
	 * 会话 ID。为空时后端会自动生成一个新会话。
	 */
	@JsonProperty("Id")
	@JsonAlias({ "id", "ID", "conversationId", "conversation_id" })
	private String id;

	/**
	 * 用户本轮输入的问题。
	 */
	@JsonProperty("Question")
	@JsonAlias({ "question", "QUESTION", "message" })
	private String question;

	/**
	 * Agent 场景。为空时默认使用 OPS_QUERY。
	 */
	@JsonProperty("AgentType")
	@JsonAlias({ "agentType", "agent_type", "scene" })
	private String agentType;

}
