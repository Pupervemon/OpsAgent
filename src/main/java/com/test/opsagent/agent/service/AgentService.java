package com.test.opsagent.agent.service;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.test.opsagent.agent.runtime.AgentRegistry;
import com.test.opsagent.agent.runtime.AgentScene;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.test.opsagent.agent.api.ChatRequest;
import com.test.opsagent.agent.api.SseMessage;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

/**
 * 对话编排层。
 * 职责是管理会话、把 ReactAgent 的流式输出转换为 SSE、并在完成后保存本轮上下文。
 */
@Service
public class AgentService {

	private final AgentRegistry agentRegistry;
	private final ExecutorService agentExecutor;

	/**
	 * 服务层只依赖 AgentRegistry，不直接注入某一个具体 ReactAgent。
	 * 这样后续新增 Workflow Agent、Supervisor Agent 或其他场景时，入口层不用重写。
	 */
	public AgentService(AgentRegistry agentRegistry, ExecutorService agentExecutor) {
		this.agentRegistry = agentRegistry;
		this.agentExecutor = agentExecutor;
	}

	/**
	 * 创建 SSE 连接，并把真正的模型调用放到后台线程执行，避免阻塞 Web 请求线程。
	 */
	public SseEmitter stream(ChatRequest request) {
		String sessionId = normalizeSessionId(request.getId());
		SseEmitter emitter = new SseEmitter(300000L);

		agentExecutor.execute(() -> runStream(request, sessionId, emitter));
		return emitter;
	}

	/**
	 * 清理会话。
	 * 改造点2：由于使用了基于 ThreadId 的 MemorySaver，通常“开启新对话”的最佳实践是
	 * 让前端直接生成并传入一个新的 conversationId (UUID) 即可，旧的记录会留在内存中（或定期过期）。
	 * 如果非要提供一个清理接口，可以直接通知前端换个 ID。
	 */
	public void clearConversation(String sessionId) {
		// 如果后续使用了 RedisSaver 等，可以在这里调用 saver 的清除 API。
		// 目前 MemorySaver 场景下，建议前端直接“新建会话(生成新UUID)”即可。
	}

	/**
	 * 流式处理逻辑
	 */
	private void runStream(ChatRequest request, String sessionId, SseEmitter emitter) {
		try {
			// 默认 OPS_QUERY；未知场景也会回退到 OPS_QUERY，保持接口兼容。
			AgentScene scene = AgentScene.from(request.getAgentType());
			ReactAgent selectedAgent = agentRegistry.get(scene);
			RunnableConfig config = RunnableConfig.builder()
					// 给 threadId 增加场景前缀，避免未来不同 Agent 共享同一会话记忆。
					.threadId(scene.name().toLowerCase() + ":" + sessionId)
					.build();

			// 调用单例 Agent 获取 Flux 流
			selectedAgent.stream(request.getQuestion(), config).subscribe(
					output -> handleOutput(output, emitter, sessionId),
					error -> {
						send(emitter, SseMessage.error(error.getMessage(), sessionId));
						emitter.completeWithError(error);
					},
					() -> {
						send(emitter, SseMessage.done(sessionId));
						emitter.complete();
					});
		}
		catch (Exception ex) {
			send(emitter, SseMessage.error(ex.getMessage(), sessionId));
			emitter.complete();
		}
	}

	/**
	 * ReactAgent 会输出多种节点事件，这里只把模型增量文本转发给前端。
	 */
	private void handleOutput(NodeOutput output, SseEmitter emitter, String sessionId) {
		if (!(output instanceof StreamingOutput streamingOutput)) {
			return;
		}
		if (streamingOutput.getOutputType() != OutputType.AGENT_MODEL_STREAMING) {
			return;
		}

		String chunk = streamingOutput.message().getText();
		if (chunk == null || chunk.isEmpty()) {
			return;
		}

		// 改造点5：移除了 assistantMessage 的 StringBuilder 手动拼接。
		// 我们现在只需专心把 Chunk 推给前端即可，持久化交给框架。
		send(emitter, SseMessage.content(chunk, sessionId));
	}

	/**
	 * 如果前端没有传会话 ID，就为本次对话创建一个。
	 */
	private String normalizeSessionId(String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			return UUID.randomUUID().toString();
		}
		return conversationId;
	}

	/**
	 * SseEmitter 的发送可能因为浏览器断开连接而失败，这里直接结束连接即可。
	 */
	private void send(SseEmitter emitter, SseMessage message) {
		try {
			emitter.send(SseEmitter.event().name("message").data(message, MediaType.APPLICATION_JSON));
		}
		catch (IOException ignored) {
			emitter.complete();
		}
	}

}
