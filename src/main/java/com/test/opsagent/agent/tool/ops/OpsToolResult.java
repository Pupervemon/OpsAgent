package com.test.opsagent.agent.tool.ops;

import java.util.Map;

/**
 * 运维工具统一返回结构。
 * <p>
 * 工具不要直接把异常抛给模型，而是返回 success/error/unavailable，
 * 让 Agent 能如实告诉用户当前能力不可用或证据不足。
 */
public record OpsToolResult(boolean success, String tool, String summary, Object data, String error) {

    public static OpsToolResult ok(String tool, String summary, Object data) {
        return new OpsToolResult(true, tool, summary, data, null);
    }

    public static OpsToolResult failed(String tool, String error) {
        return new OpsToolResult(false, tool, error, Map.of(), error);
    }

    public static OpsToolResult unavailable(String tool, String reason) {
        return new OpsToolResult(false, tool, "Unavailable: " + reason, Map.of("reason", reason), reason);
    }
}
