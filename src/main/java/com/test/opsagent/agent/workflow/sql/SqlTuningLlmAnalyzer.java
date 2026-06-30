package com.test.opsagent.agent.workflow.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * SQL 调优大模型分析器。
 * <p>
 * 该组件不使用 ReActAgent，也不使用工具调用；它只接收 Workflow 已采集好的上下文，
 * 用固定提示词调用 ChatModel，并要求模型返回结构化 JSON。
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
                你是 MySQL SQL 调优分析器。

                约束：
                1. 你只能基于用户提供的 SQL、慢查询统计、EXPLAIN 原始字段、表结构和索引信息进行分析。
                2. 不要编造不存在的表、字段、索引或执行计划。
                3. 不要建议直接在生产环境执行 DDL。
                4. 不要把单个 EXPLAIN 字段直接等同于最终结论，必须说明证据和不确定性。
                5. 必须逐字段分析 EXPLAIN 中出现的关键字段。
                6. 每个优化建议必须包含 evidence、risk、verification。
                7. 如果证据不足，必须写入 insufficientEvidence。
                8. 只输出 JSON，不要 Markdown，不要输出 JSON 之外的任何解释。

                请按以下 JSON 结构输出：
                {
                  "summary": "一句话总结",
                  "fieldAnalysis": [
                    {
                      "field": "字段名，例如 type/key/rows/Extra",
                      "observed": "观察到的值",
                      "analysis": "解释该字段含义和可能影响，不要过度下结论",
                      "confidence": "low|medium|high"
                    }
                  ],
                  "possibleCauses": [
                    {
                      "cause": "可能原因",
                      "evidence": ["证据1", "证据2"],
                      "confidence": "low|medium|high"
                    }
                  ],
                  "recommendations": [
                    {
                      "type": "SQL_REWRITE|INDEX|STATISTICS|BUSINESS|OBSERVATION",
                      "recommendation": "建议内容",
                      "evidence": ["证据1", "证据2"],
                      "risk": "风险",
                      "verification": "验证方式"
                    }
                  ],
                  "risks": ["风险1", "风险2"],
                  "verificationPlan": ["步骤1", "步骤2"],
                  "insufficientEvidence": ["缺少的信息1"]
                }

                输入上下文 JSON：
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