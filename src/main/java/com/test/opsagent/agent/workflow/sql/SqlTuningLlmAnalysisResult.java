package com.test.opsagent.agent.workflow.sql;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条 SQL 的大模型分析结果。
 * <p>
 * parsed=true 表示模型返回了合法 JSON；parsed=false 时 rawOutput 会保留原始输出，analysis 中放兜底结果。
 */
public class SqlTuningLlmAnalysisResult {

    private String status;

    private boolean parsed;

    private Map<String, Object> analysis = new LinkedHashMap<>();

    private String rawOutput;

    private String error;

    public static SqlTuningLlmAnalysisResult success(Map<String, Object> analysis, String rawOutput) {
        SqlTuningLlmAnalysisResult result = new SqlTuningLlmAnalysisResult();
        result.setStatus("SUCCESS");
        result.setParsed(true);
        result.setAnalysis(analysis);
        result.setRawOutput(rawOutput);
        return result;
    }

    public static SqlTuningLlmAnalysisResult fallback(String status, String error, String rawOutput,
            Map<String, Object> analysis) {
        SqlTuningLlmAnalysisResult result = new SqlTuningLlmAnalysisResult();
        result.setStatus(status);
        result.setParsed(false);
        result.setError(error);
        result.setRawOutput(rawOutput);
        result.setAnalysis(analysis);
        return result;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isParsed() {
        return parsed;
    }

    public void setParsed(boolean parsed) {
        this.parsed = parsed;
    }

    public Map<String, Object> getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Map<String, Object> analysis) {
        this.analysis = analysis;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public void setRawOutput(String rawOutput) {
        this.rawOutput = rawOutput;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}