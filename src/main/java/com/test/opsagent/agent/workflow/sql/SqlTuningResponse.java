package com.test.opsagent.agent.workflow.sql;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 调优响应。
 * <p>
 * 使用结构化字段承载每个节点的结果，方便前端逐段展示，也方便后续做审计。
 */
@Setter
@Getter
public class SqlTuningResponse {

    private String status;

    private String message;

    private boolean requiresApproval;

    private Map<String, Object> connection = new LinkedHashMap<>();

    private Map<String, Object> slowQueryConfig = new LinkedHashMap<>();

    private Map<String, Object> enablePlan = new LinkedHashMap<>();

    private Map<String, Object> enableResult = new LinkedHashMap<>();

    private List<Map<String, Object>> slowSqlSummary = new ArrayList<>();

    private List<Map<String, Object>> analyses = new ArrayList<>();

    private String report;

    public static SqlTuningResponse of(String status, String message) {
        SqlTuningResponse response = new SqlTuningResponse();
        response.setStatus(status);
        response.setMessage(message);
        return response;
    }

}