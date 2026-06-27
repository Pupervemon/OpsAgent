package com.test.opsagent.agent.tool.ops;

import java.util.Map;
import java.util.Optional;

import com.test.opsagent.agent.config.OpsQueryProperties;
import org.springframework.stereotype.Component;

/**
 * 服务白名单解析器。
 * <p>
 * 所有运维工具都通过它把 serviceName 解析成配置，避免工具接受任意路径、URL 或服务名。
 */
@Component
class OpsServiceResolver {

    private final OpsQueryProperties properties;

    OpsServiceResolver(OpsQueryProperties properties) {
        this.properties = properties;
    }

    Optional<OpsQueryProperties.Service> resolve(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(properties.getServices().get(serviceName.trim()));
    }

    Map<String, OpsQueryProperties.Service> services() {
        return properties.getServices();
    }

    String unavailableMessage(String serviceName) {
        if (properties.getServices().isEmpty()) {
            return "No allowed services configured under agent.ops.services";
        }
        return "Service is not in the allowed service list: " + serviceName;
    }
}
