package com.test.opsagent.agent.tool.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 运维工具 JSON 序列化辅助类。
 */
public final class OpsJson {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OpsJson() {
    }

    public static String stringify(OpsToolResult result) {
        try {
            // 工具统一返回 JSON 字符串，模型可以稳定读取 success、summary、data、error 字段。
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (JsonProcessingException ex) {
            // 即使序列化失败，也返回合法 JSON，避免工具异常中断整个 Agent 响应。
            return "{\"success\":false,\"tool\":\"serialization\",\"summary\":\"Failed to serialize tool result\",\"error\":\""
                    + safe(ex.getMessage()) + "\"}";
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

