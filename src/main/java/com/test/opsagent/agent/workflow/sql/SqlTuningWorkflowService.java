package com.test.opsagent.agent.workflow.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * SQL 调优第一版 Workflow。
 * <p>
 * 该服务按固定节点执行：连接检查、慢查询配置检查、慢 SQL 采集、SQL 聚合、EXPLAIN 和报告生成。
 * LLM 不参与流程控制，避免模型自由执行数据库动作。
 */
@Service
public class SqlTuningWorkflowService {

    /**
     * MySQL慢查询相关配置变量列表
     * slow_query_log: 慢查询日志开关
     * long_query_time: 慢查询阈值（秒）
     * log_output: 日志输出方式（FILE/TABLE/NONE）
     * slow_query_log_file: 慢查询日志文件路径
     * min_examined_row_limit: 最小检查行数限制
     * log_queries_not_using_indexes: 是否记录未使用索引的查询
     */
    private static final List<String> SLOW_QUERY_VARIABLES = List.of(
            "slow_query_log",
            "long_query_time",
            "log_output",
            "slow_query_log_file",
            "min_examined_row_limit",
            "log_queries_not_using_indexes");

    /**
     * SQL语句中提取表名的正则表达式模式
     * 匹配 FROM 或 JOIN 关键字后面的表名（支持反引号和点号）
     */
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join)\\s+([`\\w.]+)");

    /**
     * SQL调优配置属性对象，用于读取配置文件中的默认值
     */
    private final SqlTuningProperties properties;

    /**
     * 大模型分析器，只负责基于 Workflow 已采集的上下文生成结构化分析，不访问数据库。
     */
    private final SqlTuningLlmAnalyzer llmAnalyzer;

    public SqlTuningWorkflowService(SqlTuningProperties properties, SqlTuningLlmAnalyzer llmAnalyzer) {
        this.properties = properties;
        this.llmAnalyzer = llmAnalyzer;
    }

    /**
     * 执行SQL调优工作流的主入口方法
     * 
     * 工作流程：
     * 1. 解析并验证数据库连接信息
     * 2. 建立数据库连接
     * 3. 检查慢查询日志配置状态
     * 4. 如果未启用，生成启用方案并等待用户确认
     * 5. 如果已启用但输出方式不是TABLE，返回不支持错误
     * 6. 从mysql.slow_log表采集慢查询记录
     * 7. 对慢SQL进行聚合和排序
     * 8. 对Top 5慢SQL进行EXPLAIN分析
     * 9. 生成调优报告
     * 
     * @param request 调优请求参数，包含连接信息和配置选项
     * @return 调优响应结果，包含配置信息、慢SQL列表、分析结果和报告
     */
    public SqlTuningResponse run(SqlTuningRequest request) {
        // 解析连接信息（优先使用请求参数，其次使用配置文件的默认值）
        ConnectionInfo connectionInfo = resolveConnectionInfo(request);
        // 检查必填字段是否完整
        List<String> missing = connectionInfo.missingFields();
        if (!missing.isEmpty()) {
            // 如果配置不完整，返回需要配置的响应
            SqlTuningResponse response = SqlTuningResponse.of("NEEDS_CONFIG",
                    "MySQL连接配置不完整: " + String.join(", ", missing));
            response.setConnection(connectionInfo.safeMap());
            return response;
        }

        // 尝试建立数据库连接并执行调优流程
        try (Connection connection = DriverManager.getConnection(jdbcUrl(connectionInfo), jdbcProperties(connectionInfo))) {
            // 连接成功，构建初始响应
            SqlTuningResponse response = SqlTuningResponse.of("CONNECTED", "已连接到目标MySQL数据库。");
            response.setConnection(readConnectionSummary(connection, connectionInfo));

            // 读取当前慢查询配置
            Map<String, Object> slowConfig = readSlowQueryConfig(connection);
            response.setSlowQueryConfig(slowConfig);

            // 检查慢查询日志是否启用
            if (!isSlowQueryEnabled(slowConfig)) {
                // 未启用时，生成启用方案
                Map<String, Object> plan = buildEnablePlan(request);
                response.setEnablePlan(plan);
                // 需要用户确认后才能执行启用命令
                if (!Boolean.TRUE.equals(request.getApproveEnableSlowQuery())) {
                    response.setStatus("REQUIRES_APPROVAL");
                    response.setMessage("慢查询日志未启用。请审查启用方案并确认后执行SET GLOBAL命令。");
                    response.setRequiresApproval(true);
                    response.setReport(buildEnablePlanReport(response));
                    return response;
                }

                // 用户已确认，执行启用方案
                Map<String, Object> enableResult = executeEnablePlan(connection, plan);
                response.setEnableResult(enableResult);
                // 重新读取配置以确认更改生效
                response.setSlowQueryConfig(readSlowQueryConfig(connection));
                response.setStatus("SLOW_QUERY_ENABLED");
                response.setMessage("慢查询日志已启用。请等待业务流量产生慢SQL后再次运行此工作流。");
                response.setReport(buildEnableResultReport(response));
                return response;
            }

            // 检查日志输出方式是否为TABLE（第一版仅支持TABLE方式）
            if (!isTableLogOutput(slowConfig)) {
                response.setStatus("UNSUPPORTED_LOG_OUTPUT");
                response.setMessage("慢查询日志已启用，但log_output不是TABLE。第一版仅支持读取mysql.slow_log表。");
                response.setReport(buildUnsupportedLogOutputReport(response));
                return response;
            }

            // 从mysql.slow_log表中获取慢查询记录
            List<SlowSqlRecord> records = fetchSlowSql(connection, limit(request));
            if (records.isEmpty()) {
                // 没有慢查询记录
                response.setStatus("NO_SLOW_SQL");
                response.setMessage("慢查询日志已启用，但mysql.slow_log在请求范围内没有记录。");
                response.setReport(buildNoSlowSqlReport(response));
                return response;
            }

            // 对慢SQL进行聚合分组和优先级排序
            List<SlowSqlGroup> groups = rankSlowSql(records);
            response.setSlowSqlSummary(groups.stream().map(SlowSqlGroup::toMap).toList());

            // 对Top 5的慢SQL进行详细分析（EXPLAIN等）
            List<Map<String, Object>> analyses = new ArrayList<>();
            for (SlowSqlGroup group : groups.stream().limit(5).toList()) {
                analyses.add(analyzeSlowSql(connection, group));
            }
            analyses.parallelStream().forEach(this::attachLlmAnalysis);
            response.setAnalyses(analyses);
            response.setStatus("COMPLETED");
            response.setMessage("SQL调优工作流已完成。");
            response.setReport(buildAnalysisReport(response));
            return response;
        }
        catch (SQLException ex) {
            // 捕获SQL异常（连接失败或执行错误）
            SqlTuningResponse response = SqlTuningResponse.of("CONNECTION_OR_SQL_ERROR", ex.getMessage());
            response.setConnection(connectionInfo.safeMap());
            response.setReport("## SQL 调优失败\n\n无法完成 Workflow：" + ex.getMessage());
            return response;
        }
    }

    /**
     * 解析数据库连接信息
     * 优先级：请求参数 > 配置文件默认值
     * 
     * @param request 调优请求对象
     * @return 完整的连接信息对象
     */
    private ConnectionInfo resolveConnectionInfo(SqlTuningRequest request) {
        String host = firstNonBlank(request.getHost(), properties.getHost());
        int port = request.getPort() != null && request.getPort() > 0 ? request.getPort()
                : properties.getPort() > 0 ? properties.getPort() : properties.getDefaultPort();
        String database = firstNonBlank(request.getDatabase(), properties.getDatabase());
        String username = firstNonBlank(request.getUsername(), properties.getUsername());
        String password = firstNonBlank(request.getPassword(), properties.getPassword());
        String environment = firstNonBlank(request.getEnvironment(), "unknown");
        return new ConnectionInfo(host, port, database, username, password, environment);
    }

    /**
     * 构建JDBC连接URL
     * 包含字符编码、时区、超时等必要参数
     * 
     * @param info 连接信息对象
     * @return JDBC URL字符串
     */
    private String jdbcUrl(ConnectionInfo info) {
        return "jdbc:mysql://" + info.host() + ":" + info.port() + "/" + info.database()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + Math.max(properties.getConnectTimeoutSeconds(), 1) * 1000
                + "&socketTimeout=" + Math.max(properties.getSocketTimeoutSeconds(), 1) * 1000;
    }

    /**
     * 构建JDBC连接属性对象
     * 设置用户名和密码
     * 
     * @param info 连接信息对象
     * @return JDBC连接属性
     */
    private Properties jdbcProperties(ConnectionInfo info) {
        Properties jdbcProperties = new Properties();
        jdbcProperties.setProperty("user", info.username());
        jdbcProperties.setProperty("password", info.password());
        return jdbcProperties;
    }

    /**
     * 读取数据库连接摘要信息
     * 包括主机、端口、数据库、当前用户、MySQL版本等
     * 
     * @param connection 数据库连接
     * @param info 连接信息对象
     * @return 连接摘要信息的Map
     * @throws SQLException SQL执行异常
     */
    private Map<String, Object> readConnectionSummary(Connection connection, ConnectionInfo info) throws SQLException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("host", info.host());
        data.put("port", info.port());
        data.put("database", info.database());
        data.put("environment", info.environment());
        data.put("user", scalar(connection, "SELECT CURRENT_USER()"));
        data.put("version", scalar(connection, "SELECT VERSION()"));
        data.put("status", "connected");
        return data;
    }

    /**
     * 读取慢查询相关配置变量
     * 通过SHOW VARIABLES命令逐个查询配置项
     * 
     * @param connection 数据库连接
     * @return 包含所有慢查询配置的Map，额外添加enabled和tableOutput两个布尔标志
     * @throws SQLException SQL执行异常
     */
    private Map<String, Object> readSlowQueryConfig(Connection connection) throws SQLException {
        Map<String, Object> variables = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SHOW VARIABLES LIKE ?")) {
            for (String variable : SLOW_QUERY_VARIABLES) {
                statement.setString(1, variable);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        variables.put(resultSet.getString(1), resultSet.getString(2));
                    }
                    else {
                        variables.put(variable, null);
                    }
                }
            }
        }
        // 添加便捷判断标志
        variables.put("enabled", isSlowQueryEnabled(variables));
        variables.put("tableOutput", isTableLogOutput(variables));
        return variables;
    }

    /**
     * 判断慢查询日志是否启用
     * 
     * @param slowConfig 慢查询配置Map
     * @return true表示已启用
     */
    private boolean isSlowQueryEnabled(Map<String, Object> slowConfig) {
        return "ON".equalsIgnoreCase(String.valueOf(slowConfig.get("slow_query_log")));
    }

    /**
     * 判断日志输出方式是否包含TABLE
     * 第一版仅支持从mysql.slow_log表读取数据
     * 
     * @param slowConfig 慢查询配置Map
     * @return true表示输出方式为TABLE
     */
    private boolean isTableLogOutput(Map<String, Object> slowConfig) {
        return String.valueOf(slowConfig.get("log_output")).toUpperCase(Locale.ROOT).contains("TABLE");
    }

    /**
     * 构建启用慢查询日志的方案
     * 生成需要执行的SET GLOBAL命令列表
     * 
     * @param request 调优请求对象，可自定义配置参数
     * @return 启用方案Map，包含命令列表、风险提示、回滚命令等
     */
    private Map<String, Object> buildEnablePlan(SqlTuningRequest request) {
        // 确定long_query_time的值（优先使用请求参数，否则使用配置默认值）
        int longQueryTime = request.getLongQueryTimeSeconds() != null && request.getLongQueryTimeSeconds() > 0
                ? request.getLongQueryTimeSeconds() : properties.getDefaultLongQueryTimeSeconds();
        // 确定min_examined_row_limit的值
        int minRows = request.getMinExaminedRowLimit() != null && request.getMinExaminedRowLimit() >= 0
                ? request.getMinExaminedRowLimit() : properties.getDefaultMinExaminedRowLimit();
        // 确定log_output的值
        String logOutput = firstNonBlank(request.getLogOutput(), properties.getDefaultLogOutput());

        // 构建需要执行的SET GLOBAL命令列表
        List<String> commands = List.of(
                "SET GLOBAL slow_query_log = 'ON'",
                "SET GLOBAL long_query_time = " + longQueryTime,
                "SET GLOBAL log_output = '" + logOutput + "'",
                "SET GLOBAL min_examined_row_limit = " + minRows);

        // 构建方案详情
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("commands", commands);
        plan.put("temporary", true);  // SET GLOBAL是临时性的，重启后失效
        plan.put("requiresApproval", true);  // 需要用户确认
        plan.put("reason", "慢查询日志未启用。在收集慢SQL之前必须先启用它。");
        plan.put("risk", "此操作会修改MySQL全局运行时变量。SET GLOBAL是临时性的（重启后失效），可能会增加慢日志量。");
        plan.put("rollback", List.of("SET GLOBAL slow_query_log = 'OFF'"));
        return plan;
    }

    /**
     * 执行启用慢查询日志的方案
     * 逐条执行SET GLOBAL命令，并记录每条命令的执行结果
     * 
     * @param connection 数据库连接
     * @param plan 启用方案Map
     * @return 执行结果Map，包含整体成功标志和各命令的执行详情
     */
    private Map<String, Object> executeEnablePlan(Connection connection, Map<String, Object> plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> executions = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String> commands = (List<String>) plan.get("commands");
        try (Statement statement = connection.createStatement()) {
            // 逐条执行命令
            for (String command : commands) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("command", command);
                try {
                    statement.execute(command);
                    item.put("success", true);
                }
                catch (SQLException ex) {
                    item.put("success", false);
                    item.put("error", ex.getMessage());
                }
                executions.add(item);
            }
            // 判断是否所有命令都执行成功
            result.put("success", executions.stream().allMatch(item -> Boolean.TRUE.equals(item.get("success"))));
            result.put("executions", executions);
        }
        catch (SQLException ex) {
            result.put("success", false);
            result.put("error", ex.getMessage());
            result.put("executions", executions);
        }
        return result;
    }

    /**
     * 从mysql.slow_log表中获取慢查询记录
     * 按照start_time降序排列，限制返回数量
     * 
     * @param connection 数据库连接
     * @param limit 最大返回记录数
     * @return 慢查询记录列表
     * @throws SQLException SQL执行异常
     */
    private List<SlowSqlRecord> fetchSlowSql(Connection connection, int limit) throws SQLException {
        // 构建SQL查询语句，从mysql.slow_log表读取慢查询信息
        String sql = "SELECT start_time, user_host, query_time, lock_time, rows_sent, rows_examined, db, sql_text "
                + "FROM mysql.slow_log ORDER BY start_time DESC LIMIT ?";
        List<SlowSqlRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // 设置LIMIT参数
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                // 遍历结果集，将每行数据转换为SlowSqlRecord对象
                while (rs.next()) {
                    records.add(new SlowSqlRecord(
                            String.valueOf(rs.getObject("start_time")),
                            rs.getString("user_host"),
                            rs.getString("query_time"),
                            rs.getString("lock_time"),
                            rs.getLong("rows_sent"),
                            rs.getLong("rows_examined"),
                            rs.getString("db"),
                            rs.getString("sql_text")));
                }
            }
        }
        return records;
    }

    /**
     * 对慢SQL进行聚合分组和优先级排序
     * 通过fingerprint（指纹）将相似的SQL归为一组，计算统计指标
     * 
     * @param records 原始慢查询记录列表
     * @return 按优先级降序排列的慢SQL分组列表
     */
    private List<SlowSqlGroup> rankSlowSql(List<SlowSqlRecord> records) {
        // 使用LinkedHashMap保持插入顺序，key为SQL指纹，value为分组对象
        Map<String, SlowSqlGroup> groups = new LinkedHashMap<>();
        for (SlowSqlRecord record : records) {
            // 生成SQL指纹（将具体值替换为占位符）
            String fingerprint = fingerprint(record.sqlText());
            // 如果该指纹不存在则创建新分组，然后将记录添加到分组中
            groups.computeIfAbsent(fingerprint, key -> new SlowSqlGroup(key, record.sqlText(), record.db()))
                    .add(record);
        }
        // 按优先级分数降序排序，返回排序后的分组列表
        return groups.values().stream()
                .sorted(Comparator.comparingDouble(SlowSqlGroup::priorityScore).reversed())
                .toList();
    }

    /**
     * 对单个慢SQL分组进行详细分析
     * 包括EXPLAIN执行计划、表结构、索引信息和诊断建议
     * 
     * @param connection 数据库连接
     * @param group 慢SQL分组对象
     * @return 分析结果Map，包含执行计划、发现、建议、风险等信息
     */
    private Map<String, Object> analyzeSlowSql(Connection connection, SlowSqlGroup group) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        // 添加基本信息
        analysis.put("fingerprint", group.fingerprint());
        analysis.put("sampleSql", group.sampleSql());
        analysis.put("database", group.database());
        analysis.put("summary", group.toMap());

        // 检查SQL是否安全可执行EXPLAIN（第一版仅支持SELECT语句）
        if (!isSafeExplainSelect(group.sampleSql())) {
            analysis.put("supported", false);
            analysis.put("message", "第一版仅对安全的SELECT或WITH SELECT语句执行EXPLAIN。");
            return analysis;
        }

        // 提取SQL中涉及的表名
        Set<String> tables = extractTables(group.sampleSql());
        analysis.put("tables", tables);
        // 收集表结构信息（SHOW CREATE TABLE）
        Map<String, Object> schemas = collectSchemas(connection, tables);
        Map<String, Object> indexes = collectIndexes(connection, tables);
        analysis.put("schemas", schemas);
        analysis.put("indexes", indexes);

        try {
            // 执行EXPLAIN获取执行计划
            List<Map<String, Object>> explain = queryForMaps(connection, "EXPLAIN " + group.sampleSql());
            analysis.put("explain", explain);
            // 诊断执行计划中的问题
            List<Map<String, Object>> explainFacts = explainFacts(explain);
            analysis.put("explainFacts", explainFacts);
            // 根据发现的问题生成优化建议
            SqlTuningAnalysisContext llmContext = new SqlTuningAnalysisContext();
            llmContext.setFingerprint(group.fingerprint());
            llmContext.setSampleSql(group.sampleSql());
            llmContext.setDatabase(group.database());
            llmContext.setSlowSqlSummary(group.toMap());
            llmContext.setExplainFacts(explainFacts);
            llmContext.setSchemas(schemas);
            llmContext.setIndexes(indexes);
            analysis.put("llmContext", llmContext);
            analysis.put("suggestions", suggestions(group));
            analysis.put("risks", risks());
            analysis.put("verification", verificationPlan());
        }
        catch (SQLException ex) {
            // 如果EXPLAIN执行失败，记录错误信息
            analysis.put("explainError", ex.getMessage());
        }
        return analysis;
    }

    /**
     * 收集表的Schema信息（SHOW CREATE TABLE）
     * 用于了解表结构、字段类型、主键等
     *
     * @return 表结构信息Map，key为表名，value为CREATE TABLE语句或错误信息
     */
    private void attachLlmAnalysis(Map<String, Object> analysis) {
        Object context = analysis.get("llmContext");
        if (!(context instanceof SqlTuningAnalysisContext llmContext)) {
            return;
        }
        SqlTuningLlmAnalysisResult result = llmAnalyzer.analyze(llmContext);
        analysis.put("llmAnalysis", result);
        analysis.remove("llmContext");
    }
    private Map<String, Object> collectSchemas(Connection connection, Set<String> tables) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        for (String table : tables) {
            // 安全检查表名标识符，防止SQL注入
            if (!isSafeIdentifierPath(table)) {
                schemas.put(table, "跳过不安全的表标识符。");
                continue;
            }
            try {
                // 执行SHOW CREATE TABLE获取表结构定义
                schemas.put(table, queryForMaps(connection, "SHOW CREATE TABLE " + quoteIdentifierPath(table)));
            }
            catch (SQLException ex) {
                // 如果查询失败，记录错误信息
                schemas.put(table, "读取表结构失败: " + ex.getMessage());
            }
        }
        return schemas;
    }

    /**
     * 收集表的索引信息（SHOW INDEX FROM）
     * 用于了解现有索引情况，辅助索引优化决策
     * 
     * @param connection 数据库连接
     * @param tables 表名集合
     * @return 索引信息Map，key为表名，value为索引详情列表或错误信息
     */
    private Map<String, Object> collectIndexes(Connection connection, Set<String> tables) {
        Map<String, Object> indexes = new LinkedHashMap<>();
        for (String table : tables) {
            // 安全检查表名标识符，防止SQL注入
            if (!isSafeIdentifierPath(table)) {
                indexes.put(table, "跳过不安全的表标识符。");
                continue;
            }
            try {
                // 执行SHOW INDEX FROM获取索引信息
                indexes.put(table, queryForMaps(connection, "SHOW INDEX FROM " + quoteIdentifierPath(table)));
            }
            catch (SQLException ex) {
                // 如果查询失败，记录错误信息
                indexes.put(table, "读取索引信息失败: " + ex.getMessage());
            }
        }
        return indexes;
    }


    /**
     * 提取 EXPLAIN 原始事实。
     * <p>
     * 这里不做“好/坏”“是否需要索引”等判断，只把 EXPLAIN 的关键字段和原始行整理出来。
     * 后续建议由专门的 LLM 分析节点基于这些事实、表结构、索引和慢查询统计生成。
     *
     * @param explainRows EXPLAIN 执行计划的原始行数据
     * @return 中性的 EXPLAIN 字段事实列表
     */
    private List<Map<String, Object>> explainFacts(List<Map<String, Object>> explainRows) {
        List<Map<String, Object>> facts = new ArrayList<>();
        int rowIndex = 0;
        for (Map<String, Object> row : explainRows) {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("rowIndex", rowIndex++);
            fact.put("id", row.get("id"));
            fact.put("selectType", firstExisting(row, "select_type", "selectType"));
            fact.put("table", row.get("table"));
            fact.put("partitions", row.get("partitions"));
            fact.put("accessType", row.get("type"));
            fact.put("possibleKeys", row.get("possible_keys"));
            fact.put("usedKey", row.get("key"));
            fact.put("keyLength", row.get("key_len"));
            fact.put("ref", row.get("ref"));
            fact.put("estimatedRows", row.get("rows"));
            fact.put("filtered", row.get("filtered"));
            fact.put("extra", firstExisting(row, "Extra", "extra"));
            fact.put("raw", row);
            facts.add(fact);
        }
        return facts;
    }

    /**
     * 第一版暂不在规则层生成调优建议。
     * <p>
     * 这里保留安全边界和验证提醒，具体 SQL 改写、索引建议后续交给受约束的 LLM 分析节点生成。
     *
     * @param group 慢 SQL 分组对象
     * @return 通用建议列表
     */
    private List<String> suggestions(SlowSqlGroup group) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("EXPLAIN 已按原始字段呈现；具体优化建议需要结合表结构、索引、慢查询统计和后续 LLM 分析节点生成。");
        if (group.sampleSql().toLowerCase(Locale.ROOT).contains("select *")) {
            suggestions.add("当前 SQL 包含 SELECT * 这一事实；是否需要减少返回列，后续应结合业务字段需求判断。");
        }
        suggestions.add("不要在生产环境直接执行索引 DDL；任何索引或 SQL 改写建议都应先在测试/预发环境验证。");
        return suggestions;
    }

    /**
     * 第一版保留通用风险提示，不基于 EXPLAIN 字段直接推导风险。
     * <p>
     * 具体风险后续由 LLM 分析节点在给出建议时同步生成。
     *
     * @return 通用风险列表
     */
    private List<String> risks() {
        List<String> risks = new ArrayList<>();
        risks.add("索引、SQL 改写和统计信息操作都可能影响生产行为，必须先在测试/预发环境验证。");
        risks.add("仅凭 EXPLAIN 字段不能直接证明某个优化动作一定有效，需要结合表规模、字段选择性、查询频率和业务语义判断。");
        return risks;
    }
    private List<String> verificationPlan() {
        return List.of(
                "保存当前EXPLAIN结果作为基线。",
                "仅在测试/预发环境中应用SQL重写或索引。",
                "再次运行EXPLAIN并比较key、rows、type和Extra字段。",
                "使用代表性参数验证结果集一致性。",
                "部署后观察慢查询日志以确认频率和延迟下降。");
    }

    /**
     * 执行SQL查询并将结果转换为Map列表
     * 通用的查询工具方法，适用于任意SELECT语句
     *
     * @return 查询结果，每行数据以Map形式存储（key为列名，value为列值）
     * @throws SQLException SQL执行异常
     */
    private Object firstExisting(Map<String, Object> row, String firstKey, String secondKey) {
        Object first = row.get(firstKey);
        return first != null ? first : row.get(secondKey);
    }
    private List<Map<String, Object>> queryForMaps(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            // 获取结果集元数据（列数、列名等）
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            // 遍历结果集的每一行
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                // 遍历每一列，将列名和列值存入Map
                for (int index = 1; index <= columnCount; index++) {
                    row.put(metaData.getColumnLabel(index), rs.getObject(index));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    /**
     * 执行标量查询（只返回单个值）
     * 适用于SELECT VERSION()、SELECT CURRENT_USER()等查询
     * 
     * @param connection 数据库连接
     * @param sql SQL查询语句
     * @return 查询结果的第一行第一列的值，如果无结果则返回null
     * @throws SQLException SQL执行异常
     */
    private Object scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    /**
     * 获取慢查询采集的数量限制
     * 优先使用请求参数，其次使用配置默认值，并确保在合理范围内
     * 
     * @param request 调优请求对象
     * @return 实际的LIMIT值
     */
    private int limit(SqlTuningRequest request) {
        // 如果请求中没有指定limit或值为负数，使用配置默认值
        int value = request.getLimit() == null || request.getLimit() <= 0 ? properties.getDefaultLimit() : request.getLimit();
        // 确保返回值在[1, maxLimit]范围内
        return Math.min(Math.max(value, 1), Math.max(properties.getMaxLimit(), 1));
    }

    /**
     * 生成SQL指纹（fingerprint）
     * 将SQL中的字面量（字符串、数字）替换为占位符，用于归类相似SQL
     * 例如：SELECT * FROM users WHERE id=123 和 SELECT * FROM users WHERE id=456 会被归为同一类
     * 
     * @param sql 原始SQL语句
     * @return 标准化后的SQL指纹（小写、去除多余空格、字面量替换为?）
     */
    private String fingerprint(String sql) {
        if (sql == null) {
            return "";
        }
        // 将单引号字符串替换为?
        return sql.replaceAll("'([^'\\\\]|\\\\.)*'", "?")
                // 将双引号字符串替换为?
                .replaceAll("\"([^\"\\\\]|\\\\.)*\"", "?")
                // 将数字（整数和小数）替换为?
                .replaceAll("\\b\\d+(?:\\.\\d+)?\\b", "?")
                // 将多个连续空格替换为单个空格
                .replaceAll("\\s+", " ")
                // 去除首尾空格并转为小写
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 检查SQL是否可以安全执行EXPLAIN
     * 第一版仅支持SELECT或WITH开头的查询，排除危险操作
     * 
     * @param sql SQL语句
     * @return true表示可以安全执行EXPLAIN
     */
    private boolean isSafeExplainSelect(String sql) {
        if (sql == null) {
            return false;
        }
        // 转为小写并去除首尾空格
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        // 必须以SELECT或WITH开头
        if (!(normalized.startsWith("select") || normalized.startsWith("with"))) {
            return false;
        }
        // 排除包含危险操作的SQL：分号、INTO OUTFILE、BENCHMARK、SLEEP、LOAD_FILE等
        return !normalized.contains(";")
                && !normalized.contains(" into outfile")
                && !normalized.contains(" benchmark(")
                && !normalized.contains(" sleep(")
                && !normalized.contains(" load_file(");
    }

    /**
     * 从SQL语句中提取表名
     * 通过正则表达式匹配FROM和JOIN关键字后面的表名
     * 
     * @param sql SQL语句
     * @return 表名集合（已去除反引号）
     */
    private Set<String> extractTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            // 去除表名中的反引号后加入集合
            tables.add(matcher.group(1).replace("`", ""));
        }
        return tables;
    }

    /**
     * 检查标识符路径是否安全（防止SQL注入）
     * 只允许字母、数字、下划线、美元符号、点号和反引号
     * 
     * @param value 待检查的标识符
     * @return true表示标识符安全可用
     */
    private boolean isSafeIdentifierPath(String value) {
        return value != null && value.matches("[A-Za-z0-9_$.`]+") && !value.contains(";");
    }

    /**
     * 为标识符路径添加反引号引用
     * 支持schema.table格式，每个部分都单独加反引号
     * 例如：my_db.users -> `my_db`.`users`
     * 
     * @param value 原始标识符路径
     * @return 添加反引号后的标识符路径
     */
    private String quoteIdentifierPath(String value) {
        // 先去除已有的反引号
        String normalized = value.replace("`", "");
        // 按点号分割为多个部分
        String[] parts = normalized.split("\\.");
        List<String> quoted = new ArrayList<>();
        // 为每个部分添加反引号
        for (String part : parts) {
            quoted.add("`" + part + "`");
        }
        return String.join(".", quoted);
    }

    /**
     * 构建启用慢查询日志方案的报告
     * 生成Markdown格式的报告，包含建议命令和风险提示
     * 
     * @param response 调优响应对象
     * @return Markdown格式的报告文本
     */
    private String buildEnablePlanReport(SqlTuningResponse response) {
        return "## 慢查询未开启\n\n"
                + "当前 MySQL 未开启慢查询日志，需要用户确认后才能执行开启命令。\n\n"
                + "### 建议命令\n\n```sql\n"
                + String.join(";\n", castStringList(response.getEnablePlan().get("commands")))
                + ";\n```\n\n"
                + "### 风险\n\n" + response.getEnablePlan().get("risk");
    }

    /**
     * 构建慢查询日志已成功启用的报告
     * 
     * @param response 调优响应对象
     * @return Markdown格式的报告文本
     */
    private String buildEnableResultReport(SqlTuningResponse response) {
        return "## 慢查询已开启\n\n已执行用户确认的 SET GLOBAL 命令。请等待业务流量产生慢 SQL 后再次运行调优 Workflow。";
    }

    /**
     * 构建不支持的日志输出方式的报告
     * 当log_output不是TABLE时调用
     * 
     * @param response 调优响应对象
     * @return Markdown格式的报告文本
     */
    private String buildUnsupportedLogOutputReport(SqlTuningResponse response) {
        return "## 暂不支持当前慢日志输出方式\n\n慢查询已开启，但 log_output 不是 TABLE。第一版只读取 mysql.slow_log。\n\n当前配置："
                + response.getSlowQueryConfig();
    }

    /**
     * 构建暂无慢SQL的报告
     * 当mysql.slow_log表中没有记录时调用
     * 
     * @param response 调优响应对象
     * @return Markdown格式的报告文本
     */
    private String buildNoSlowSqlReport(SqlTuningResponse response) {
        return "## 暂无慢 SQL\n\n慢查询已开启，但 mysql.slow_log 当前没有可分析记录。可能原因：刚开启、业务流量不足、long_query_time 阈值过高。";
    }

    /**
     * 构建完整的SQL调优分析报告
     * 包含配置信息、Top慢SQL列表、详细分析结果等
     * 
     * @param response 调优响应对象
     * @return Markdown格式的完整报告文本
     */
    private String buildAnalysisReport(SqlTuningResponse response) {
        StringBuilder builder = new StringBuilder();
        builder.append("## SQL 调优报告\n\n");
        // 第一部分：慢查询配置
        builder.append("### 1. 慢查询配置\n\n").append(response.getSlowQueryConfig()).append("\n\n");
        // 第二部分：Top慢SQL摘要
        builder.append("### 2. Top 慢 SQL\n\n");
        for (Map<String, Object> item : response.getSlowSqlSummary().stream().limit(5).toList()) {
            builder.append("- fingerprint: `").append(item.get("fingerprint")).append("`, count: ")
                    .append(item.get("count")).append(", avgQueryTimeSeconds: ")
                    .append(item.get("avgQueryTimeSeconds")).append(", avgRowsExamined: ")
                    .append(item.get("avgRowsExamined")).append("\n");
        }
        // 第三部分：详细分析结果
        builder.append("\n### 3. 分析结果\n\n");
        for (Map<String, Object> analysis : response.getAnalyses()) {
            builder.append("#### SQL\n\n```sql\n").append(analysis.get("sampleSql")).append("\n```\n\n");
            builder.append("EXPLAIN 原始字段：").append(analysis.get("explainFacts")).append("\n\n");
            builder.append("建议：").append(analysis.get("suggestions")).append("\n\n");
            builder.append("风险：").append(analysis.get("risks")).append("\n\n");
            builder.append("验证：").append(analysis.get("verification")).append("\n\n");
        }
        return builder.toString();
    }

    /**
     * 将Object类型的值安全地转换为String列表
     * 用于从Map中提取List类型的字段
     * 
     * @param value 待转换的对象
     * @return String列表，如果转换失败则返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * 获取第一个非空字符串
     * 优先返回first，如果first为空则返回second
     * 
     * @param first 首选字符串
     * @param second 备选字符串
     * @return 第一个非空字符串
     */
    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    /**
     * 检查字符串是否为空或空白
     * 
     * @param value 待检查的字符串
     * @return true表示字符串为null或只包含空白字符
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 将Object转换为long类型
     * 支持Number类型和字符串解析
     * 
     * @param value 待转换的对象
     * @return 转换后的long值，如果转换失败则返回0
     */
    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * 解析MySQL时间格式字符串为秒数
     * 支持HH:MM:SS格式和纯数字格式
     * 
     * @param mysqlTime MySQL时间字符串（如"00:00:05"表示5秒）
     * @return 对应的秒数，如果解析失败则返回0
     */
    private double parseSeconds(String mysqlTime) {
        if (mysqlTime == null || mysqlTime.isBlank()) {
            return 0;
        }
        String[] parts = mysqlTime.split(":");
        try {
            // 如果是HH:MM:SS格式，分别解析时、分、秒
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600D + Integer.parseInt(parts[1]) * 60D + Double.parseDouble(parts[2]);
            }
            // 如果是纯数字，直接解析
            return Double.parseDouble(mysqlTime);
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * 数据库连接信息记录类
     * 封装MySQL连接所需的所有参数，并提供验证和安全输出功能
     */
    private record ConnectionInfo(String host, int port, String database, String username, String password,
            String environment) {

        /**
         * 检查必填字段是否完整
         * 验证host、database、username、password是否为空
         * 
         * @return 缺失的字段名列表，如果返回空列表表示所有必填字段都已提供
         */
        List<String> missingFields() {
            List<String> missing = new ArrayList<>();
            if (host == null || host.isBlank()) {
                missing.add("host");
            }
            if (database == null || database.isBlank()) {
                missing.add("database");
            }
            if (username == null || username.isBlank()) {
                missing.add("username");
            }
            if (password == null || password.isBlank()) {
                missing.add("password");
            }
            return missing;
        }

        /**
         * 生成安全的连接信息Map（不包含密码明文）
         * 用于日志输出或API响应，避免泄露敏感信息
         * 
         * @return 包含连接信息的Map，其中密码字段用passwordConfigured布尔值代替
         */
        Map<String, Object> safeMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("host", host);
            data.put("port", port);
            data.put("database", database);
            data.put("username", username);
            data.put("passwordConfigured", password != null && !password.isBlank());
            data.put("environment", environment);
            return data;
        }
    }

    /**
     * 慢SQL记录类
     * 封装从mysql.slow_log表中读取的单条慢查询记录
     * 包含执行时间、用户、锁时间、扫描行数等关键指标
     */
    private record SlowSqlRecord(String startTime, String userHost, String queryTime, String lockTime, long rowsSent,
            long rowsExamined, String db, String sqlText) {
    }

    /**
     * 慢SQL分组类
     * 将具有相同指纹（fingerprint）的相似SQL归为一组，并计算统计指标
     * 用于识别高频慢SQL和评估优化优先级
     */
    private class SlowSqlGroup {

        /** SQL指纹（标准化后的SQL模板） */
        private final String fingerprint;

        /** 示例SQL（该分组中的第一条SQL） */
        private final String sampleSql;

        /** 数据库名 */
        private final String database;

        /** 出现次数 */
        private int count;

        /** 总查询时间（秒） */
        private double totalQueryTimeSeconds;

        /** 最大查询时间（秒） */
        private double maxQueryTimeSeconds;

        /** 总扫描行数 */
        private long totalRowsExamined;

        /** 最大扫描行数 */
        private long maxRowsExamined;

        /** 总返回行数 */
        private long totalRowsSent;

        /**
         * 构造函数
         * 
         * @param fingerprint SQL指纹
         * @param sampleSql 示例SQL语句
         * @param database 数据库名
         */
        SlowSqlGroup(String fingerprint, String sampleSql, String database) {
            this.fingerprint = fingerprint;
            this.sampleSql = sampleSql;
            this.database = database;
        }

        /**
         * 添加一条慢SQL记录到当前分组
         * 累加统计数据并更新最大值
         * 
         * @param record 待添加的慢SQL记录
         */
        void add(SlowSqlRecord record) {
            // 增加计数
            count++;
            // 解析查询时间并累加
            double querySeconds = parseSeconds(record.queryTime());
            totalQueryTimeSeconds += querySeconds;
            // 更新最大查询时间
            maxQueryTimeSeconds = Math.max(maxQueryTimeSeconds, querySeconds);
            // 累加扫描行数并更新最大值
            totalRowsExamined += record.rowsExamined();
            maxRowsExamined = Math.max(maxRowsExamined, record.rowsExamined());
            // 累加返回行数
            totalRowsSent += record.rowsSent();
        }

        /**
         * 计算优先级分数
         * 综合考虑出现频率、平均耗时、最大耗时和扫描行数
         * 分数越高表示该SQL越需要优先优化
         * 
         * @return 优先级分数（越大越优先）
         */
        double priorityScore() {
            // 公式：出现次数 × 平均耗时 + 最大耗时 + 平均扫描行数 / 10000
            return count * avgQueryTimeSeconds() + maxQueryTimeSeconds + avgRowsExamined() / 10000D;
        }

        /**
         * 计算平均查询时间
         * 
         * @return 平均查询时间（秒），如果count为0则返回0
         */
        double avgQueryTimeSeconds() {
            return count == 0 ? 0 : totalQueryTimeSeconds / count;
        }

        /**
         * 计算平均扫描行数
         * 
         * @return 平均扫描行数，如果count为0则返回0
         */
        long avgRowsExamined() {
            return count == 0 ? 0 : totalRowsExamined / count;
        }

        /**
         * 计算平均返回行数
         * 
         * @return 平均返回行数，如果count为0则返回0
         */
        long avgRowsSent() {
            return count == 0 ? 0 : totalRowsSent / count;
        }

        /**
         * 获取SQL指纹
         * 
         * @return SQL指纹字符串
         */
        String fingerprint() {
            return fingerprint;
        }

        /**
         * 获取示例SQL
         * 
         * @return 示例SQL语句
         */
        String sampleSql() {
            return sampleSql;
        }

        /**
         * 获取数据库名
         * 
         * @return 数据库名
         */
        String database() {
            return database;
        }

        /**
         * 将分组信息转换为Map格式
         * 包含所有统计指标，用于JSON序列化或报告生成
         * 
         * @return 包含分组统计信息的Map
         */
        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fingerprint", fingerprint);
            data.put("sampleSql", sampleSql);
            data.put("database", database);
            data.put("count", count);
            data.put("avgQueryTimeSeconds", avgQueryTimeSeconds());
            data.put("maxQueryTimeSeconds", maxQueryTimeSeconds);
            data.put("avgRowsExamined", avgRowsExamined());
            data.put("maxRowsExamined", maxRowsExamined);
            data.put("avgRowsSent", avgRowsSent());
            data.put("priorityScore", priorityScore());
            return data;
        }
    }
}