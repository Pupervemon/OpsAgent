package com.test.opsagent.agent.tool.ops;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.test.opsagent.agent.config.OpsQueryProperties;
import org.springframework.stereotype.Component;

/**
 * 受控命令执行器。
 * <p>
 * 这里不接收模型生成的任意 shell 字符串，只执行调用方构造好的固定命令数组。
 * 命令不存在、超时或被中断时返回 unavailable，方便 Agent 如实说明。
 */
@Component
class OpsCommandRunner {

    private final OpsQueryProperties properties;

    OpsCommandRunner(OpsQueryProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行固定命令数组，不经过 shell，因此不会解析管道、重定向等 shell 语法。
     */
    CommandResult run(List<String> command, File directory) {
        try {
            // ProcessBuilder 接收命令数组，不接收完整 shell 字符串，避免模型拼出管道、重定向等 shell 语法。
            ProcessBuilder builder = new ProcessBuilder(command);
            if (directory != null) {
                // 工作目录来自服务白名单配置，不由用户或模型临时指定。
                builder.directory(directory);
            }
            builder.redirectErrorStream(true);

            Process process = builder.start();
            int timeout = Math.max(properties.getCommandTimeoutSeconds(), 1);
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                // 超时直接终止进程，避免工具调用卡住 SSE 流式请求。
                process.destroyForcibly();
                return CommandResult.unavailable("Command timed out after " + timeout + " seconds");
            }

            // redirectErrorStream(true) 已经把 stderr 合并到 stdout，这里统一读取给 Agent 分析。
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new CommandResult(true, process.exitValue(), output, null);
        }
        catch (IOException ex) {
            // 常见情况：服务器没有安装 systemctl/git，或当前应用用户没有执行权限。
            return CommandResult.unavailable("Command is unavailable or not installed: " + command.get(0));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CommandResult.unavailable("Command was interrupted");
        }
        catch (Exception ex) {
            return CommandResult.unavailable(ex.getMessage());
        }
    }

    record CommandResult(boolean available, int exitCode, String output, String error) {

        // available=false 表示命令本身无法运行；exitCode 非 0 则表示命令运行了但结果失败。
        static CommandResult unavailable(String error) {
            return new CommandResult(false, -1, "", error);
        }

        boolean success() {
            return available && exitCode == 0;
        }
    }
}

