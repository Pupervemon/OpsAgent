package com.test.opsagent.agent.api;

import com.test.opsagent.agent.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP 接口层。
 * 这里只做参数校验和路由转发，不直接碰模型调用逻辑，方便后续替换前端或扩展更多接口。
 */
@RestController
@RequestMapping("/api")
class AgentController {

	private final AgentService agentService;

	AgentController(AgentService agentService) {
		this.agentService = agentService;
	}

	/**
	 * 流式对话接口。
	 * 前端用 fetch 读取 text/event-stream，后端通过 SseEmitter 持续推送模型增量内容。
	 */
	@PostMapping(path = "/chat_stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter stream(@RequestBody ChatRequest request) {
		if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is required");
		}
		return agentService.stream(request);
	}

	/**
	 * 清理指定会话的上下文记忆。
	 */
	@DeleteMapping("/chat/session/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void clearConversation(@PathVariable String sessionId) {
		agentService.clearConversation(sessionId);
	}

}
