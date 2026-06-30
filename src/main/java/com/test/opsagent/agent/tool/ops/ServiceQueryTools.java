package com.test.opsagent.agent.tool.ops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.test.opsagent.agent.config.OpsQueryProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 服务状态和健康检查工具。
 * <p>
 * 只允许查询配置在 agent.ops.services 下的服务。
 * systemctl 或健康检查不可用时返回 unavailable，不把底层异常直接暴露给用户。
 */
@Component
public class ServiceQueryTools {

    private final OpsServiceResolver resolver;

    private final OpsCommandRunner commandRunner;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public ServiceQueryTools(OpsServiceResolver resolver, OpsCommandRunner commandRunner) {
        this.resolver = resolver;
        this.commandRunner = commandRunner;
    }

    /**
     * 让 Agent 先知道当前有哪些服务可以查询，避免它猜服务名。
     */
    @Tool(description = "List service names that are allowed for read-only operations queries.")
    public String listAllowedServices() {
        return OpsJson.stringify(OpsToolResult.ok("listAllowedServices",
                "Listed configured services allowed for operations queries.", resolver.services().keySet()));
    }

    @Tool(description = "Get the read-only systemd status of an allowed service. Returns unavailable if systemctl is not installed or the service is not configured.")
    public String getServiceStatus(
            @ToolParam(description = "Allowed service name configured under agent.ops.services") String serviceName) {
        return resolver.resolve(serviceName)
                .map(service -> statusFor(serviceName, service))
                .orElseGet(() -> OpsJson.stringify(OpsToolResult.unavailable("getServiceStatus",
                        resolver.unavailableMessage(serviceName))));
    }

    @Tool(description = "Check the configured health endpoint of an allowed service.")
    public String checkServiceHealth(
            @ToolParam(description = "Allowed service name configured under agent.ops.services") String serviceName) {
        return resolver.resolve(serviceName)
                .map(service -> healthFor(serviceName, service))
                .orElseGet(() -> OpsJson.stringify(OpsToolResult.unavailable("checkServiceHealth",
                        resolver.unavailableMessage(serviceName))));
    }

    private String statusFor(String serviceName, OpsQueryProperties.Service service) {
        if (service.getSystemdName() == null || service.getSystemdName().isBlank()) {
            return OpsJson.stringify(OpsToolResult.unavailable("getServiceStatus",
                    "No systemdName configured for service: " + serviceName));
        }

        // 固定调用 systemctl status，不接受模型拼接的任意命令。
        OpsCommandRunner.CommandResult result = commandRunner.run(
                List.of("systemctl", "status", service.getSystemdName(), "--no-pager"), null);
        if (!result.available()) {
            return OpsJson.stringify(OpsToolResult.unavailable("getServiceStatus", result.error()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serviceName", serviceName);
        data.put("systemdName", service.getSystemdName());
        data.put("exitCode", result.exitCode());
        data.put("output", result.output());
        return OpsJson.stringify(OpsToolResult.ok("getServiceStatus",
                result.success() ? "Service status command succeeded." : "Service status command returned non-zero.",
                data));
    }

    private String healthFor(String serviceName, OpsQueryProperties.Service service) {
        if (service.getHealthUrl() == null || service.getHealthUrl().isBlank()) {
            return OpsJson.stringify(OpsToolResult.unavailable("checkServiceHealth",
                    "No healthUrl configured for service: " + serviceName));
        }

        try {
            // 只对配置好的 healthUrl 发起 GET 请求，并设置超时，避免长时间阻塞。
            HttpRequest request = HttpRequest.newBuilder(URI.create(service.getHealthUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serviceName", serviceName);
            data.put("healthUrl", service.getHealthUrl());
            data.put("statusCode", response.statusCode());
            // 响应体保留下来，方便 Agent 判断具体健康检查失败原因。
            data.put("body", response.body());
            return OpsJson.stringify(OpsToolResult.ok("checkServiceHealth",
                    response.statusCode() >= 200 && response.statusCode() < 400
                            ? "Health endpoint returned a successful status code."
                            : "Health endpoint returned a non-success status code.",
                    data));
        }
        catch (Exception ex) {
            return OpsJson.stringify(OpsToolResult.unavailable("checkServiceHealth",
                    "Health endpoint check failed: " + ex.getMessage()));
        }
    }
}
