package com.test.opsagent.agent.workflow.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * SQL tuning LLM analyzer.
 * <p>
 * This component does not use ReActAgent or tool calls. It only analyzes the context collected by the workflow.
 */
@Component
public class SqlTuningLlmAnalyzer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatModel chatModel;

    private final ObjectMapper objectMapper;

    public SqlTuningLlmAnalyzer(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public SqlTuningLlmAnalysisResult analyze(SqlTuningAnalysisContext context) {
        String prompt;
        try {
            prompt = buildPrompt(context);
        }
        catch (Exception ex) {
            return fallback("PROMPT_BUILD_FAILED", "Failed to build LLM prompt: " + ex.getMessage(), null, context);
        }

        String rawOutput = null;
        try {
            rawOutput = chatModel.call(prompt);
            String json = extractJson(rawOutput);
            Map<String, Object> analysis = objectMapper.readValue(json, MAP_TYPE);
            return SqlTuningLlmAnalysisResult.success(analysis, rawOutput);
        }
        catch (Exception ex) {
            return fallback("LLM_ANALYSIS_FAILED", ex.getMessage(), rawOutput, context);
        }
    }

    private String buildPrompt(SqlTuningAnalysisContext context) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sql", context.getSampleSql());
        payload.put("fingerprint", context.getFingerprint());
        payload.put("database", context.getDatabase());
        payload.put("slowSqlSummary", context.getSlowSqlSummary());
        payload.put("explainFacts", context.getExplainFacts());
        payload.put("schemas", context.getSchemas());
        payload.put("indexes", context.getIndexes());

        String contextJson = objectMapper.writeValueAsString(payload);
        return """
                You are a MySQL SQL tuning analyzer.

                You must follow these rules:
                1. Analyze only the provided SQL, slow query statistics, EXPLAIN facts, table schemas, and index metadata.
                2. Do not invent tables, columns, indexes, values, EXPLAIN rows, or business facts.
                3. Do not recommend executing DDL directly in production.
                4. Do not treat a single EXPLAIN field as a final conclusion. Explain uncertainty and evidence.
                5. Analyze the EXPLAIN facts field by field when they are present.
                6. Every recommendation must include evidence, risk, and verification.
                7. If evidence is insufficient, list it in insufficientEvidence.
                8. Output strict JSON only. Do not output Markdown or any text outside the JSON object.
                9. Write the JSON string values in Chinese.

                Required JSON schema:
                {
                  "summary": "one sentence summary in Chinese",
                  "fieldAnalysis": [
                    {
                      "field": "EXPLAIN field name, for example type/key/rows/Extra",
                      "observed": "observed value",
                      "analysis": "meaning and possible impact, with uncertainty when needed",
                      "confidence": "low|medium|high"
                    }
                  ],
                  "possibleCauses": [
                    {
                      "cause": "possible cause in Chinese",
                      "evidence": ["evidence item 1", "evidence item 2"],
                      "confidence": "low|medium|high"
                    }
                  ],
                  "recommendations": [
                    {
                      "type": "SQL_REWRITE|INDEX|STATISTICS|BUSINESS|OBSERVATION",
                      "recommendation": "recommendation in Chinese",
                      "evidence": ["evidence item 1", "evidence item 2"],
                      "risk": "risk in Chinese",
                      "verification": "verification method in Chinese"
                    }
                  ],
                  "risks": ["risk item in Chinese"],
                  "verificationPlan": ["verification step in Chinese"],
                  "insufficientEvidence": ["missing evidence in Chinese"]
                }

                Input context JSON:
                %s
                """.formatted(contextJson);
    }

    private String extractJson(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new IllegalArgumentException("LLM returned empty output");
        }
        String trimmed = rawOutput.trim();
        if (trimmed.startsWith("```")) {
            int firstBrace = trimmed.indexOf('{');
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        throw new IllegalArgumentException("LLM output does not contain a JSON object");
    }

    private SqlTuningLlmAnalysisResult fallback(String status, String error, String rawOutput,
            SqlTuningAnalysisContext context) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("summary", "大模型分析失败，当前仅返回 Workflow 已采集的 EXPLAIN 原始字段和慢查询统计。");
        analysis.put("fieldAnalysis", List.of());
        analysis.put("possibleCauses", List.of());
        analysis.put("recommendations", List.of(Map.of(
                "type", "OBSERVATION",
                "recommendation", "请先查看 explainFacts、schemas、indexes 和 slowSqlSummary；稍后可重试大模型分析。",
                "evidence", List.of("LLM analysis failed"),
                "risk", "不能基于失败的大模型调用生成具体 SQL 或索引建议。",
                "verification", "修复模型调用后重新运行分析。")));
        analysis.put("risks", List.of("当前没有模型生成的风险评估。"));
        analysis.put("verificationPlan", List.of("保留当前 EXPLAIN 结果作为基线。"));
        analysis.put("insufficientEvidence", List.of("LLM analysis unavailable: " + error));
        if (context != null) {
            analysis.put("fingerprint", context.getFingerprint());
        }
        return SqlTuningLlmAnalysisResult.fallback(status, error, rawOutput, analysis);
    }
}