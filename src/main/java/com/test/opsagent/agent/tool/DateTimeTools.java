package com.test.opsagent.agent.tool;

import java.time.LocalDateTime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 示例工具类。
 * Spring AI 会读取 @Tool 注解，自动生成工具描述和调用参数 schema，供 ReactAgent 决定何时调用。
 */
@Component
@Slf4j
public class DateTimeTools {

	/**
	 * 获取当前时间。用户问“现在几点/今天日期”等问题时，模型可以自动调用这个工具。
	 */
	@Tool(description = "Get the current date and time in the user's timezone")
	public String getCurrentDateTime() {
		log.info("调用获取时间工具类");
		return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
	}

}
