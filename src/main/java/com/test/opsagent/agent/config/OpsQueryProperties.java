package com.test.opsagent.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 运维查询工具配置。
 * <p>
 * 所有服务、日志路径、健康检查地址都必须先配置到白名单中。
 * Agent 只能按 serviceName 查询这些配置，不能自由传入任意路径或命令。
 */
@Component
@ConfigurationProperties(prefix = "agent.ops")
public class OpsQueryProperties {

    private int commandTimeoutSeconds = 5;

    private int maxLogLines = 200;

    private Map<String, Service> services = new LinkedHashMap<>();

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public int getMaxLogLines() {
        return maxLogLines;
    }

    public void setMaxLogLines(int maxLogLines) {
        this.maxLogLines = maxLogLines;
    }

    public Map<String, Service> getServices() {
        return services;
    }

    public void setServices(Map<String, Service> services) {
        this.services = services == null ? new LinkedHashMap<>() : services;
    }

    public static class Service {

        /**
         * systemd 服务名。服务器没有 systemctl 或服务不存在时，工具会返回 unavailable。
         */
        private String systemdName;

        /**
         * 健康检查地址。未配置时健康检查工具会明确说明不可用。
         */
        private String healthUrl;

        /**
         * 允许读取的日志文件路径。日志工具只读取这里配置的路径。
         */
        private String logPath;

        /**
         * 可选版本文件路径，用于非 git 部署场景记录版本号、构建号或 Gitee 产物信息。
         */
        private String versionFile;

        /**
         * 可选工作目录。只有配置后才会尝试读取 git 分支和 commit。
         */
        private String workingDirectory;

        public String getSystemdName() {
            return systemdName;
        }

        public void setSystemdName(String systemdName) {
            this.systemdName = systemdName;
        }

        public String getHealthUrl() {
            return healthUrl;
        }

        public void setHealthUrl(String healthUrl) {
            this.healthUrl = healthUrl;
        }

        public String getLogPath() {
            return logPath;
        }

        public void setLogPath(String logPath) {
            this.logPath = logPath;
        }

        public String getVersionFile() {
            return versionFile;
        }

        public void setVersionFile(String versionFile) {
            this.versionFile = versionFile;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }
    }
}
