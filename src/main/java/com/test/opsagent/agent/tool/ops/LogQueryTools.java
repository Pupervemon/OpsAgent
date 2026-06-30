package com.test.opsagent.agent.tool.ops;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.test.opsagent.agent.config.OpsQueryProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 日志查询工具。
 * <p>
 * 只读取服务白名单中配置的 logPath，不允许用户或模型传入任意路径。
 * 日志不存在、不可读或编码异常时返回 unavailable。
 */
@Component
public class LogQueryTools {

    private final OpsQueryProperties properties;

    private final OpsServiceResolver resolver;

    public LogQueryTools(OpsQueryProperties properties, OpsServiceResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    /**
     * 读取最近日志行数，并按配置限制最大返回量，避免一次性把大日志推给模型。
     */
    @Tool(description = "Read recent log lines for an allowed service. Returns unavailable if no log path is configured or the file cannot be read.")
    public String tailServiceLog(
            @ToolParam(description = "Allowed service name configured under agent.ops.services") String serviceName,
            @ToolParam(description = "Number of recent lines to read. The tool clamps this to the configured maximum.") int lines) {
        return resolver.resolve(serviceName)
                .map(service -> tail(serviceName, service, lines))
                .orElseGet(() -> OpsJson.stringify(OpsToolResult.unavailable("tailServiceLog",
                        resolver.unavailableMessage(serviceName))));
    }

    @Tool(description = "Search a keyword in the configured log file for an allowed service.")
    public String searchServiceLog(
            @ToolParam(description = "Allowed service name configured under agent.ops.services") String serviceName,
            @ToolParam(description = "Keyword to search in service logs") String keyword,
            @ToolParam(description = "Maximum matching lines to return. The tool clamps this to the configured maximum.") int maxLines) {
        return resolver.resolve(serviceName)
                .map(service -> search(serviceName, service, keyword, maxLines))
                .orElseGet(() -> OpsJson.stringify(OpsToolResult.unavailable("searchServiceLog",
                        resolver.unavailableMessage(serviceName))));
    }

    private String tail(String serviceName, OpsQueryProperties.Service service, int requestedLines) {
        // logPath 只从服务配置中读取，不暴露为 @Tool 参数。
        Path path = logPath(service);
        if (path == null) {
            return OpsJson.stringify(OpsToolResult.unavailable("tailServiceLog",
                    "No logPath configured for service: " + serviceName));
        }
        // 文件不存在或不可读时返回 unavailable，让用户知道当前日志能力不可用。
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return OpsJson.stringify(OpsToolResult.unavailable("tailServiceLog",
                    "Log file does not exist or is not readable: " + path));
        }

        int limit = clampLines(requestedLines);
        try (var stream = Files.lines(path, StandardCharsets.UTF_8)) {
            // 使用固定大小队列保留最后 N 行，避免把整个日志文件返回给模型。
            ArrayDeque<String> buffer = new ArrayDeque<>(limit);
            stream.forEach(line -> {
                if (buffer.size() == limit) {
                    buffer.removeFirst();
                }
                buffer.addLast(line);
            });

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serviceName", serviceName);
            data.put("logPath", path.toString());
            data.put("lines", new ArrayList<>(buffer));
            return OpsJson.stringify(OpsToolResult.ok("tailServiceLog",
                    "Read recent service log lines.", data));
        }
        catch (Exception ex) {
            return OpsJson.stringify(OpsToolResult.unavailable("tailServiceLog",
                    "Failed to read log file: " + ex.getMessage()));
        }
    }

    private String search(String serviceName, OpsQueryProperties.Service service, String keyword, int requestedLines) {
        if (keyword == null || keyword.isBlank()) {
            return OpsJson.stringify(OpsToolResult.failed("searchServiceLog", "Keyword is required"));
        }

        // logPath 只从服务配置中读取，不暴露为 @Tool 参数。
        Path path = logPath(service);
        if (path == null) {
            return OpsJson.stringify(OpsToolResult.unavailable("searchServiceLog",
                    "No logPath configured for service: " + serviceName));
        }
        // 文件不存在或不可读时返回 unavailable，让用户知道当前日志能力不可用。
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return OpsJson.stringify(OpsToolResult.unavailable("searchServiceLog",
                    "Log file does not exist or is not readable: " + path));
        }

        int limit = clampLines(requestedLines);
        List<String> matches = new ArrayList<>();
        try (var stream = Files.lines(path, StandardCharsets.UTF_8)) {
            // 第一版返回从文件开头匹配到的前 N 行；后续可以按时间窗口或倒序扫描优化。
            stream.filter(line -> line.contains(keyword))
                    .limit(limit)
                    .forEach(matches::add);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serviceName", serviceName);
            data.put("logPath", path.toString());
            data.put("keyword", keyword);
            data.put("matches", matches);
            return OpsJson.stringify(OpsToolResult.ok("searchServiceLog",
                    "Searched service log for keyword.", data));
        }
        catch (Exception ex) {
            return OpsJson.stringify(OpsToolResult.unavailable("searchServiceLog",
                    "Failed to search log file: " + ex.getMessage()));
        }
    }

    private Path logPath(OpsQueryProperties.Service service) {
        if (service.getLogPath() == null || service.getLogPath().isBlank()) {
            return null;
        }
        return Path.of(service.getLogPath()).normalize();
    }

    private int clampLines(int requestedLines) {
        // 返回行数受 agent.ops.max-log-lines 控制，防止提示词上下文被日志撑爆。
        int max = Math.max(properties.getMaxLogLines(), 1);
        if (requestedLines <= 0) {
            return Math.min(50, max);
        }
        return Math.min(requestedLines, max);
    }
}
