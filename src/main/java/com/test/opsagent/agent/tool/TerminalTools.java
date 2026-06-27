package com.test.opsagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 终端工具类，为 AI Agent 提供安全的只读终端命令执行能力。
 * <p>
 * 仅允许执行预定义的白名单命令（java-version、maven-version、git-status、rg），
 * 所有命令均在项目工作空间目录下执行，并带有超时控制和输出长度限制，
 * 防止恶意或意外操作对系统造成影响。
 * </p>
 */
@Component
public class TerminalTools {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工作空间根目录，命令将在此目录下执行 */
    @Value("${agent.terminal.workspace-root:.}")
    private String workspaceRoot;

    /** 命令执行超时时间（秒） */
    @Value("${agent.terminal.timeout-seconds:5}")
    private long timeoutSeconds;

    /** 命令输出最大字符数，超出部分将被截断 */
    @Value("${agent.terminal.max-output-chars:8000}")
    private int maxOutputChars;

    /** 允许执行的命令白名单 */
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "java-version",
            "maven-version",
            "git-status",
            "rg"
    );

    /**
     * 在项目工作空间内执行安全的只读终端命令。
     *
     * @param command 允许的命令名称：java-version、maven-version、git-status、rg
     * @param args    命令参数，仅用于 rg 命令，不允许包含 shell 操作符
     * @return JSON 格式的执行结果字符串，包含 success、exitCode、command、output 字段
     */
    @Tool(description = "Run a safe, read-only terminal command inside the project workspace. Allowed commands: java-version, maven-version, git-status, rg.")
    public String runTerminalCommand(
            @ToolParam(description = "Allowed command name: java-version, maven-version, git-status, rg") String command,
            @ToolParam(description = "Command arguments. Only used for rg. Do not include shell operators.") List<String> args
    ) {
        try {
            if (!ALLOWED_COMMANDS.contains(command)) {
                return error("Command not allowed: " + command);
            }

            List<String> processCommand = buildCommand(command, args == null ? List.of() : args);

            ProcessBuilder builder = new ProcessBuilder(processCommand);
            builder.directory(new File(workspaceRoot));
            builder.redirectErrorStream(true); // 合并标准输出和错误输出

            Process process = builder.start();

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return error("Command timed out after " + timeoutSeconds + " seconds");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (output.length() > maxOutputChars) {
                output = output.substring(0, maxOutputChars) + "\n...[truncated]";
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", process.exitValue() == 0);
            result.put("exitCode", process.exitValue());
            result.put("command", processCommand);
            result.put("output", output);

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    /**
     * 根据命令名称构建实际的进程执行命令列表。
     *
     * @param command 命令名称
     * @param args    命令参数
     * @return 用于 ProcessBuilder 执行的命令列表
     */
    private List<String> buildCommand(String command, List<String> args) {
        return switch (command) {
            case "java-version" -> List.of("java", "-version");
            case "maven-version" -> List.of("mvn", "-version");
            case "git-status" -> List.of("git", "status", "--short", "--untracked-files=all");
            case "rg" -> buildRgCommand(args);
            default -> throw new IllegalArgumentException("Unsupported command: " + command);
        };
    }

    /**
     * 构建 rg（ripgrep）搜索命令，并对参数进行安全校验，
     * 防止路径穿越（..）和 shell 注入（|、&、;）等危险操作。
     *
     * @param args rg 命令参数
     * @return 完整的 rg 命令列表
     */
    private List<String> buildRgCommand(List<String> args) {
        for (String arg : args) {
            if (arg.contains("..") || arg.contains("|") || arg.contains("&") || arg.contains(";")) {
                throw new IllegalArgumentException("Unsafe rg argument: " + arg);
            }
        }

        List<String> command = new ArrayList<>();
        command.add("rg");
        command.addAll(args);
        return command;
    }

    /**
     * 构建错误响应的 JSON 字符串。
     *
     * @param message 错误信息
     * @return JSON 格式的错误响应
     */
    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", message
            ));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + message + "\"}";
        }
    }
}
