package com.test.opsagent.agent.tool.ops;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.test.opsagent.agent.config.OpsQueryProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 应用版本查询工具。
 * <p>
 * 兼容两类部署方式：有 git 工作目录时读取 commit/branch；
 * 没有 git 或使用 Gitee/制品部署时，优先读取配置的 versionFile。
 */
@Component
public class AppVersionTools {

    private final OpsServiceResolver resolver;

    private final OpsCommandRunner commandRunner;

    public AppVersionTools(OpsServiceResolver resolver, OpsCommandRunner commandRunner) {
        this.resolver = resolver;
        this.commandRunner = commandRunner;
    }

    @Tool(description = "Get application version information for an allowed service from a configured version file and optional git working directory.")
    public String getAppVersion(
            @ToolParam(description = "Allowed service name configured under agent.ops.services") String serviceName) {
        return resolver.resolve(serviceName)
                .map(service -> versionFor(serviceName, service))
                .orElseGet(() -> OpsJson.stringify(OpsToolResult.unavailable("getAppVersion",
                        resolver.unavailableMessage(serviceName))));
    }

    private String versionFor(String serviceName, OpsQueryProperties.Service service) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serviceName", serviceName);

        // 非 git 部署时，建议由发布流程写入 VERSION 文件。
        if (service.getVersionFile() != null && !service.getVersionFile().isBlank()) {
            // versionFile 适合制品部署场景：发布流程写入版本号，这里只负责读取。
            Path versionPath = Path.of(service.getVersionFile()).normalize();
            data.put("versionFile", versionPath.toString());
            if (Files.isRegularFile(versionPath) && Files.isReadable(versionPath)) {
                try {
                    data.put("versionFileContent", Files.readString(versionPath, StandardCharsets.UTF_8).trim());
                }
                catch (Exception ex) {
                    data.put("versionFileError", ex.getMessage());
                }
            }
            else {
                data.put("versionFileError", "Version file does not exist or is not readable.");
            }
        }
        else {
            data.put("versionFileError", "No versionFile configured.");
        }

        // git 不是强依赖；服务器未安装 git 时会返回明确错误。
        if (service.getWorkingDirectory() != null && !service.getWorkingDirectory().isBlank()) {
            File directory = Path.of(service.getWorkingDirectory()).normalize().toFile();
            data.put("workingDirectory", directory.getAbsolutePath());

            // git 查询通过受控命令执行器完成；git 不存在时不会抛异常给 Agent。
            OpsCommandRunner.CommandResult commit = commandRunner.run(
                    List.of("git", "rev-parse", "--short", "HEAD"), directory);
            if (commit.available()) {
                data.put("gitCommitExitCode", commit.exitCode());
                data.put("gitCommit", commit.output());
            }
            else {
                data.put("gitError", commit.error());
            }

            OpsCommandRunner.CommandResult branch = commandRunner.run(
                    List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), directory);
            if (branch.available()) {
                data.put("gitBranchExitCode", branch.exitCode());
                data.put("gitBranch", branch.output());
            }
            else {
                data.put("gitBranchError", branch.error());
            }
        }
        else {
            data.put("gitError", "No workingDirectory configured. This is expected if the server is deployed from Gitee packages or artifacts without a git checkout.");
        }

        // 这个工具会把能读取到的信息和缺失原因一起返回，方便 Agent 如实解释。
        return OpsJson.stringify(OpsToolResult.ok("getAppVersion",
                "Collected version information from configured sources. Missing sources are reported in data.", data));
    }
}
