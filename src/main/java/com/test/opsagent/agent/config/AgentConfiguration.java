package com.test.opsagent.agent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 相关基础 Bean。
 */
@Configuration
class AgentConfiguration {

	/**
	 * 使用 JDK 21 虚拟线程处理 SSE 后台任务。
	 * 每个流式请求会占用一个任务，虚拟线程比固定线程池更适合这种等待型工作。
	 */
	@Bean(destroyMethod = "close")
	ExecutorService agentExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

}
