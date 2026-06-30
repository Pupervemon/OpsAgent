package com.test.opsagent.agent.workflow.sql;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单条慢 SQL 的大模型分析上下文。
 * <p>
 * Workflow 负责把数据库里拿到的 SQL、慢查询统计、EXPLAIN 原始字段、表结构和索引整理到这里，
 * 大模型只基于这个上下文分析，不再通过工具访问数据库。
 */
@Setter
@Getter
public class SqlTuningAnalysisContext {

    private String fingerprint;

    private String sampleSql;

    private String database;

    private Map<String, Object> slowSqlSummary = new LinkedHashMap<>();

    private List<Map<String, Object>> explainFacts = List.of();

    private Map<String, Object> schemas = new LinkedHashMap<>();

    private Map<String, Object> indexes = new LinkedHashMap<>();

}