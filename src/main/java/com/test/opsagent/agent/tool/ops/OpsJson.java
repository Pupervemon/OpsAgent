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
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (JsonProcessingException ex) {
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
