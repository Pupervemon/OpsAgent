package com.test.opsagent.agent.workflow.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单条慢 SQL 的大模型分析上下文。
 * <p>
 * Workflow 负责把数据库里拿到的 SQL、慢查询统计、EXPLAIN 原始字段、表结构和索引整理到这里，
 * 大模型只基于这个上下文分析，不再通过工具访问数据库。
 */
public class SqlTuningAnalysisContext {

    private String fingerprint;

    private String sampleSql;

    private String database;

    private Map<String, Object> slowSqlSummary = new LinkedHashMap<>();

    private List<Map<String, Object>> explainFacts = List.of();

    private Map<String, Object> schemas = new LinkedHashMap<>();

    private Map<String, Object> indexes = new LinkedHashMap<>();

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSampleSql() {
        return sampleSql;
    }

    public void setSampleSql(String sampleSql) {
        this.sampleSql = sampleSql;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public Map<String, Object> getSlowSqlSummary() {
        return slowSqlSummary;
    }

    public void setSlowSqlSummary(Map<String, Object> slowSqlSummary) {
        this.slowSqlSummary = slowSqlSummary;
    }

    public List<Map<String, Object>> getExplainFacts() {
        return explainFacts;
    }

    public void setExplainFacts(List<Map<String, Object>> explainFacts) {
        this.explainFacts = explainFacts;
    }

    public Map<String, Object> getSchemas() {
        return schemas;
    }

    public void setSchemas(Map<String, Object> schemas) {
        this.schemas = schemas;
    }

    public Map<String, Object> getIndexes() {
        return indexes;
    }

    public void setIndexes(Map<String, Object> indexes) {
        this.indexes = indexes;
    }
}