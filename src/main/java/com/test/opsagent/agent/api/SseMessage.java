package com.test.opsagent.agent.api;

/**
 * 后端推给前端的 SSE 消息体。
 * type=content 表示模型增量文本，type=done 表示结束，type=error 表示异常。
 */
public record SseMessage(String type, String data, String sessionId) {

	public static SseMessage content(String data, String sessionId) {
		return new SseMessage("content", data, sessionId);
	}

	public static SseMessage error(String data, String sessionId) {
		return new SseMessage("error", data, sessionId);
	}

	public static SseMessage done(String sessionId) {
		return new SseMessage("done", null, sessionId);
	}

}
