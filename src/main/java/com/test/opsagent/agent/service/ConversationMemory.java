package com.test.opsagent.agent.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.test.opsagent.agent.model.AgentMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 简单的内存会话存储。
 * 当前适合本地 Demo：应用重启后历史会丢失；后续要持久化时可以替换为 Redis/数据库。
 */
@Component
class ConversationMemory {

	private final ConcurrentMap<String, Deque<AgentMessage>> conversations = new ConcurrentHashMap<>();
	private final int maxMessages;

	ConversationMemory(@Value("${agent.conversation.max-message-pairs:6}") int maxMessagePairs) {
		this.maxMessages = Math.max(maxMessagePairs, 1) * 2;
	}

	/**
	 * 返回历史消息副本，避免调用方直接修改内部队列。
	 */
	List<AgentMessage> getHistory(String conversationId) {
		Deque<AgentMessage> messages = conversation(conversationId);
		synchronized (messages) {
			return new ArrayList<>(messages);
		}
	}

	/**
	 * 一轮对话结束后，把用户问题和助手完整回复成对写入历史。
	 */
	void addMessagePair(String conversationId, String userMessage, String assistantMessage) {
		Deque<AgentMessage> messages = conversation(conversationId);
		synchronized (messages) {
			messages.addLast(new AgentMessage("user", userMessage));
			if (assistantMessage != null && !assistantMessage.isBlank()) {
				messages.addLast(new AgentMessage("assistant", assistantMessage));
			}
			trim(messages);
		}
	}

	/**
	 * 删除某个会话的全部历史。
	 */
	void clear(String conversationId) {
		if (conversationId != null) {
			conversations.remove(conversationId);
		}
	}

	private Deque<AgentMessage> conversation(String conversationId) {
		return conversations.computeIfAbsent(conversationId, ignored -> new ArrayDeque<>());
	}

	/**
	 * 控制上下文窗口大小，避免历史无限增长导致 prompt 过长。
	 */
	private void trim(Deque<AgentMessage> messages) {
		while (messages.size() > maxMessages) {
			messages.removeFirst();
		}
	}

}
