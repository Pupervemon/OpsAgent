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
 * SQL 调优工作流服务
 * <p>
 * 该工作流负责所有数据库交互操作，LLM 仅接收准备好的上下文并返回结构化分析结果。
 * 主要功能包括：
 * 1. 检查并启用 MySQL 慢查询日志
 * 2. 从 mysql.slow_log 表中获取慢查询记录
 * 3. 对慢查询进行分组、排序和优先级评分
 * 4. 收集表结构、索引信息和执行计划
 * 5. 调用 LLM 进行智能分析并生成优化建议
 */
@Service
public class SqlTuningWorkflowService {

    /**
     * 需要检查的慢查询相关系统变量列表
     */
    private static final List<String> SLOW_QUERY_VARIABLES = List.of(
            "slow_query_log",           // 慢查询日志开关
            "long_query_time",          // 慢查询时间阈值（秒）
            "log_output",               // 日志输出方式（FILE/TABLE/NONE）
            "slow_query_log_file",      // 慢查询日志文件路径
            "min_examined_row_limit",   // 最小检查行数阈值
            "log_queries_not_using_indexes"); // 未使用索引的查询是否记录

    /**
     * 用于从 SQL 语句中提取表名的正则表达式
     * 匹配 FROM 或 JOIN 关键字后的表名（支持反引号）
     */
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join)\\s+([`\\w.]+)");

    /**
     * SQL 调优配置属性
     */
    private final SqlTuningProperties properties;

    /**
     * LLM 分析器，用于对 SQL 进行智能分析
     */
    private final SqlTuningLlmAnalyzer llmAnalyzer;

    /**
     * 构造函数，注入依赖
     *
     * @param properties    SQL 调优配置属性
     * @param llmAnalyzer   LLM 分析器实例
     */
    public SqlTuningWorkflowService(SqlTuningProperties properties, SqlTuningLlmAnalyzer llmAnalyzer) {
        this.properties = properties;
        this.llmAnalyzer = llmAnalyzer;
    }

    /**
     * 执行 SQL 调优工作流的主入口方法
     * <p>
     * 完整工作流程：
     * 1. 解析并验证数据库连接信息
     * 2. 建立数据库连接
     * 3. 读取慢查询配置状态
     * 4. 如果慢查询未开启，则处理启用流程（需用户确认）
     * 5. 检查日志输出方式是否为 TABLE（第一版仅支持 TABLE 方式）
     * 6. 从 mysql.slow_log 获取慢查询记录
     * 7. 对慢查询进行分组和优先级排序
     * 8. 收集每个慢查询的分析数据（表结构、索引、执行计划）
     * 9. 并行调用 LLM 进行分析
     * 10. 生成分析报告并返回
     *
     * @param request SQL 调优请求，包含连接信息和配置参数
     * @return SQL 调优响应，包含分析结果和建议
     */
    public SqlTuningResponse run(SqlTuningRequest request) {
        // 解析连接信息（优先使用请求中的配置，否则回退到配置文件）
        ConnectionInfo connectionInfo = resolveConnectionInfo(request);
        // 检查必填字段是否完整
        List<String> missing = connectionInfo.missingFields();
        if (!missing.isEmpty()) {
            // 配置不完整，返回错误提示
            SqlTuningResponse response = SqlTuningResponse.of("NEEDS_CONFIG",
                    "MySQL connection configuration is incomplete: " + String.join(", ", missing));
            response.setConnection(connectionInfo.safeMap());
            return response;
        }

        // 建立数据库连接并执行工作流
        try (Connection connection = DriverManager.getConnection(jdbcUrl(connectionInfo), jdbcProperties(connectionInfo))) {
            // 构建成功连接响应，包含连接摘要信息
            SqlTuningResponse response = SqlTuningResponse.of("CONNECTED", "Connected to target MySQL.");
            response.setConnection(readConnectionSummary(connection, connectionInfo));

            // 读取当前慢查询配置状态
            Map<String, Object> slowConfig = readSlowQueryConfig(connection);
            response.setSlowQueryConfig(slowConfig);

            // 检查慢查询是否已开启
            if (!isSlowQueryEnabled(slowConfig)) {
                return handleSlowQueryDisabled(connection, request, response);
            }
            // 检查日志输出方式是否为 TABLE
            if (!isTableLogOutput(slowConfig)) {
                response.setStatus("UNSUPPORTED_LOG_OUTPUT");
                response.setMessage("Slow query log is enabled, but log_output is not TABLE. First version only reads mysql.slow_log.");
                response.setReport(buildUnsupportedLogOutputReport(response));
                return response;
            }

            // 获取慢查询记录
            List<SlowSqlRecord> records = fetchSlowSql(connection, limit(request));
            if (records.isEmpty()) {
                // 没有慢查询记录
                response.setStatus("NO_SLOW_SQL");
                response.setMessage("Slow query log is enabled, but mysql.slow_log has no records in the requested range.");
                response.setReport(buildNoSlowSqlReport(response));
                return response;
            }

            // 对慢查询进行分组和优先级排序，取前 5 个
            List<SlowSqlGroup> topGroups = rankSlowSql(records).stream().limit(5).toList();
            response.setSlowSqlSummary(topGroups.stream().map(SlowSqlGroup::toMap).toList());

            // 为每个慢查询组收集分析数据（表结构、索引、执行计划等）
            List<SqlAnalysisBundle> bundles = new ArrayList<>();
            for (SlowSqlGroup group : topGroups) {
                bundles.add(collectAnalysisBundle(connection, group));
            }

            // 并行调用 LLM 进行分析
            List<Map<String, Object>> analyses = bundles.parallelStream()
                    .map(this::analyzeBundleWithLlm)
                    .toList();

            // 设置分析结果并完成响应
            response.setAnalyses(analyses);
            response.setStatus("COMPLETED");
            response.setMessage("SQL tuning workflow completed.");
            response.setReport(buildAnalysisReport(response));
            return response;
        }
        catch (SQLException ex) {
            // 捕获 SQL 异常，返回错误信息
            SqlTuningResponse response = SqlTuningResponse.of("CONNECTION_OR_SQL_ERROR", ex.getMessage());
            response.setConnection(connectionInfo.safeMap());
            response.setReport("## SQL 调优失败\n\n无法完成 Workflow：" + ex.getMessage());
            return response;
        }
    }

    /**
     * 处理慢查询未开启的情况
     * <p>
     * 如果用户已批准启用，则执行 SET GLOBAL 命令开启慢查询；
     * 否则返回启用计划供用户确认。
     *
     * @param connection 数据库连接
     * @param request    调优请求
     * @param response   当前响应对象
     * @return 更新后的响应对象
     * @throws SQLException 数据库操作异常
     */
    private SqlTuningResponse handleSlowQueryDisabled(Connection connection, SqlTuningRequest request,
            SqlTuningResponse response) throws SQLException {
        // 构建启用慢查询的执行计划
        Map<String, Object> plan = buildEnablePlan(request);
        response.setEnablePlan(plan);
        // 检查用户是否已批准执行
        if (!Boolean.TRUE.equals(request.getApproveEnableSlowQuery())) {
            // 需要用户确认
            response.setStatus("REQUIRES_APPROVAL");
            response.setMessage("Slow query log is disabled. Review enablePlan and confirm before executing SET GLOBAL commands.");
            response.setRequiresApproval(true);
            response.setReport(buildEnablePlanReport(response));
            return response;
        }

        // 执行启用计划
        Map<String, Object> enableResult = executeEnablePlan(connection, plan);
        response.setEnableResult(enableResult);
        // 重新读取慢查询配置以确认已启用
        response.setSlowQueryConfig(readSlowQueryConfig(connection));
        response.setStatus("SLOW_QUERY_ENABLED");
        response.setMessage("Slow query log has been enabled. Wait for workload to generate slow SQL, then run this workflow again.");
        response.setReport(buildEnableResultReport(response));
        return response;
    }

    /**
     * 收集单个慢查询组的完整分析数据包
     * <p>
     * 包括：原始慢查询信息、涉及的表、表结构、索引信息、执行计划等
     *
     * @param connection 数据库连接
     * @param group      慢查询组
     * @return 分析数据包
     */
    private SqlAnalysisBundle collectAnalysisBundle(Connection connection, SlowSqlGroup group) {
        // 初始化原始数据容器
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("slowSqlSummary", group.toMap());

        // 从 SQL 中提取涉及的表名
        Set<String> tables = extractTables(group.sampleSql());
        rawData.put("tables", tables);

        // 收集表结构和索引信息
        Map<String, Object> schemas = collectSchemas(connection, tables);
        Map<String, Object> indexes = collectIndexes(connection, tables);
        rawData.put("schemas", schemas);
        rawData.put("indexes", indexes);

        // 初始化执行计划相关变量
        List<Map<String, Object>> explain = List.of();
        List<Map<String, Object>> explainFacts = List.of();
        String status = "READY_FOR_LLM";
        String message = null;

        // 安全检查：仅对 SELECT 或 WITH 语句执行 EXPLAIN
        if (!isSafeExplainSelect(group.sampleSql())) {
            status = "SKIPPED_NOT_SELECT";
            message = "First version only runs EXPLAIN for safe SELECT or WITH SELECT statements.";
        }
        else {
            // 执行 EXPLAIN 获取执行计划
            try {
                explain = queryForMaps(connection, "EXPLAIN " + group.sampleSql());
                // 将执行计划转换为事实数据格式
                explainFacts = explainFacts(explain);
            }
            catch (SQLException ex) {
                status = "EXPLAIN_FAILED";
                message = ex.getMessage();
            }
        }

        rawData.put("explain", explain);
        rawData.put("explainFacts", explainFacts);

        // 构建 LLM 分析上下文（仅在状态为 READY_FOR_LLM 时）
        SqlTuningAnalysisContext llmContext = null;
        if ("READY_FOR_LLM".equals(status)) {
            llmContext = new SqlTuningAnalysisContext();
            llmContext.setFingerprint(group.fingerprint());
            llmContext.setSampleSql(group.sampleSql());
            llmContext.setDatabase(group.database());
            llmContext.setSlowSqlSummary(group.toMap());
            llmContext.setExplainFacts(explainFacts);
            llmContext.setSchemas(schemas);
            llmContext.setIndexes(indexes);
        }

        return new SqlAnalysisBundle(group, status, message, rawData, llmContext);
    }

    /**
     * 使用 LLM 分析单个慢查询包
     * <p>
     * 将分析数据包转换为 LLM 可理解的格式，并调用 LLM 进行分析
     *
     * @param bundle 分析数据包
     * @return 包含 LLM 分析结果的 Map
     */
    private Map<String, Object> analyzeBundleWithLlm(SqlAnalysisBundle bundle) {
        // 构建分析结果项
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fingerprint", bundle.group().fingerprint());
        item.put("sampleSql", bundle.group().sampleSql());
        item.put("database", bundle.group().database());
        item.put("status", bundle.status());
        if (bundle.message() != null) {
            item.put("message", bundle.message());
        }

        // 如果有 LLM 上下文，则调用 LLM 进行分析
        if (bundle.llmContext() != null) {
            item.put("llmAnalysis", llmAnalyzer.analyze(bundle.llmContext()));
        }
        else {
            item.put("llmAnalysis", null);
        }

        item.put("rawData", bundle.rawData());
        return item;
    }

    /**
     * 解析数据库连接信息
     * <p>
     * 优先使用请求中的配置，缺失时回退到配置文件中的默认值
     *
     * @param request SQL 调优请求
     * @return 完整的连接信息对象
     */
    private ConnectionInfo resolveConnectionInfo(SqlTuningRequest request) {
        // 按优先级获取连接参数：请求 > 配置 > 默认值
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
     * 构建 JDBC 连接 URL
     * <p>
     * 包含字符编码、时区、超时等必要参数
     *
     * @param info 连接信息
     * @return JDBC URL 字符串
     */
    private String jdbcUrl(ConnectionInfo info) {
        return "jdbc:mysql://" + info.host() + ":" + info.port() + "/" + info.database()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + Math.max(properties.getConnectTimeoutSeconds(), 1) * 1000
                + "&socketTimeout=" + Math.max(properties.getSocketTimeoutSeconds(), 1) * 1000;
    }

    /**
     * 构建 JDBC 连接属性对象
     *
     * @param info 连接信息
     * @return JDBC 属性对象（包含用户名和密码）
     */
    private Properties jdbcProperties(ConnectionInfo info) {
        Properties jdbcProperties = new Properties();
        jdbcProperties.setProperty("user", info.username());
        jdbcProperties.setProperty("password", info.password());
        return jdbcProperties;
    }

    /**
     * 读取连接摘要信息
     * <p>
     * 包括主机、端口、数据库、当前用户、MySQL 版本等信息
     *
     * @param connection 数据库连接
     * @param info       连接信息
     * @return 连接摘要 Map
     * @throws SQLException 数据库操作异常
     */
    private Map<String, Object> readConnectionSummary(Connection connection, ConnectionInfo info) throws SQLException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("host", info.host());
        data.put("port", info.port());
        data.put("database", info.database());
        data.put("environment", info.environment());
        // 查询当前用户和 MySQL 版本
        data.put("user", scalar(connection, "SELECT CURRENT_USER()"));
        data.put("version", scalar(connection, "SELECT VERSION()"));
        data.put("status", "connected");
        return data;
    }

    /**
     * 读取慢查询相关配置
     * <p>
     * 通过 SHOW VARIABLES 命令获取所有慢查询相关的系统变量
     *
     * @param connection 数据库连接
     * @return 慢查询配置 Map，包含各变量值和启用状态
     * @throws SQLException 数据库操作异常
     */
    private Map<String, Object> readSlowQueryConfig(Connection connection) throws SQLException {
        Map<String, Object> variables = new LinkedHashMap<>();
        // 逐个查询慢查询相关变量
        try (PreparedStatement statement = connection.prepareStatement("SHOW VARIABLES LIKE ?")) {
            for (String variable : SLOW_QUERY_VARIABLES) {
                statement.setString(1, variable);
                try (ResultSet resultSet = statement.executeQuery()) {
                    variables.put(variable, resultSet.next() ? resultSet.getString(2) : null);
                }
            }
        }
        // 添加计算后的启用状态字段
        variables.put("enabled", isSlowQueryEnabled(variables));
        variables.put("tableOutput", isTableLogOutput(variables));
        return variables;
    }

    /**
     * 判断慢查询日志是否已开启
     *
     * @param slowConfig 慢查询配置 Map
     * @return true 表示已开启，false 表示未开启
     */
    private boolean isSlowQueryEnabled(Map<String, Object> slowConfig) {
        return "ON".equalsIgnoreCase(String.valueOf(slowConfig.get("slow_query_log")));
    }

    /**
     * 判断日志输出方式是否包含 TABLE
     * <p>
     * 第一版仅支持从 mysql.slow_log 表读取慢查询记录
     *
     * @param slowConfig 慢查询配置 Map
     * @return true 表示输出方式为 TABLE，false 表示其他方式
     */
    private boolean isTableLogOutput(Map<String, Object> slowConfig) {
        return String.valueOf(slowConfig.get("log_output")).toUpperCase(Locale.ROOT).contains("TABLE");
    }

    /**
     * 构建启用慢查询日志的执行计划
     * <p>
     * 根据请求参数或默认配置，生成需要执行的 SET GLOBAL 命令列表
     *
     * @param request SQL 调优请求，包含可选的配置覆盖参数
     * @return 执行计划 Map，包含命令列表、风险提示、回滚命令等
     */
    private Map<String, Object> buildEnablePlan(SqlTuningRequest request) {
        // 获取长查询时间阈值（优先使用请求参数，否则使用默认值）
        int longQueryTime = request.getLongQueryTimeSeconds() != null && request.getLongQueryTimeSeconds() > 0
                ? request.getLongQueryTimeSeconds() : properties.getDefaultLongQueryTimeSeconds();
        // 获取最小检查行数限制
        int minRows = request.getMinExaminedRowLimit() != null && request.getMinExaminedRowLimit() >= 0
                ? request.getMinExaminedRowLimit() : properties.getDefaultMinExaminedRowLimit();
        // 获取日志输出方式
        String logOutput = firstNonBlank(request.getLogOutput(), properties.getDefaultLogOutput());

        // 构建需要执行的 SET GLOBAL 命令列表
        List<String> commands = List.of(
                "SET GLOBAL slow_query_log = 'ON'",
                "SET GLOBAL long_query_time = " + longQueryTime,
                "SET GLOBAL log_output = '" + logOutput + "'",
                "SET GLOBAL min_examined_row_limit = " + minRows);

        // 构建执行计划对象
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("commands", commands);
        plan.put("temporary", true);  // SET GLOBAL 是临时性的，重启后失效
        plan.put("requiresApproval", true);  // 需要用户确认才能执行
        plan.put("reason", "Slow query log is disabled. Enabling it is required before collecting slow SQL.");
        plan.put("risk", "This changes MySQL global runtime variables. SET GLOBAL is temporary and may increase slow log volume.");
        plan.put("rollback", List.of("SET GLOBAL slow_query_log = 'OFF'"));  // 回滚命令
        return plan;
    }

    /**
     * 执行启用慢查询日志的计划
     * <p>
     * 逐个执行计划中的 SET GLOBAL 命令，并记录每个命令的执行结果
     *
     * @param connection 数据库连接
     * @param plan       执行计划，包含命令列表
     * @return 执行结果 Map，包含成功状态和各命令的执行详情
     */
    private Map<String, Object> executeEnablePlan(Connection connection, Map<String, Object> plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> executions = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String> commands = (List<String>) plan.get("commands");
        try (Statement statement = connection.createStatement()) {
            // 逐个执行每个 SET GLOBAL 命令
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
            // 判断所有命令是否都执行成功
            result.put("success", executions.stream().allMatch(item -> Boolean.TRUE.equals(item.get("success"))));
            result.put("executions", executions);
        }
        catch (SQLException ex) {
            // 捕获 Statement 创建异常
            result.put("success", false);
            result.put("error", ex.getMessage());
            result.put("executions", executions);
        }
        return result;
    }

    /**
     * 从 mysql.slow_log 表中获取慢查询记录
     * <p>
     * 按开始时间倒序排列，返回指定数量的最新慢查询记录
     *
     * @param connection 数据库连接
     * @param limit      返回记录数量限制
     * @return 慢查询记录列表
     * @throws SQLException 数据库操作异常
     */
    private List<SlowSqlRecord> fetchSlowSql(Connection connection, int limit) throws SQLException {
        String sql = "SELECT start_time, user_host, query_time, lock_time, rows_sent, rows_examined, db, sql_text "
                + "FROM mysql.slow_log ORDER BY start_time DESC LIMIT ?";
        List<SlowSqlRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    // 将每条记录转换为 SlowSqlRecord 对象
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
     * 对慢查询记录进行分组和优先级排序
     * <p>
     * 通过指纹（fingerprint）将相似的 SQL 归为一组，计算每组的统计信息和优先级分数
     *
     * @param records 原始慢查询记录列表
     * @return 按优先级降序排列的慢查询组列表
     */
    private List<SlowSqlGroup> rankSlowSql(List<SlowSqlRecord> records) {
        Map<String, SlowSqlGroup> groups = new LinkedHashMap<>();
        for (SlowSqlRecord record : records) {
            // 生成 SQL 指纹，用于分组相似 SQL
            String fingerprint = fingerprint(record.sqlText());
            groups.computeIfAbsent(fingerprint, key -> new SlowSqlGroup(key, record.sqlText(), record.db()))
                    .add(record);
        }
        // 按优先级分数降序排序
        return groups.values().stream()
                .sorted(Comparator.comparingDouble(SlowSqlGroup::priorityScore).reversed())
                .toList();
    }

    /**
     * 收集表结构信息
     * <p>
     * 对每个涉及的表执行 SHOW CREATE TABLE 命令获取建表语句
     *
     * @param connection 数据库连接
     * @param tables     表名集合
     * @return 表结构信息 Map，key 为表名，value 为建表语句或错误信息
     */
    private Map<String, Object> collectSchemas(Connection connection, Set<String> tables) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        for (String table : tables) {
            // 安全检查：跳过不安全的表标识符
            if (!isSafeIdentifierPath(table)) {
                schemas.put(table, "Skipped unsafe table identifier.");
                continue;
            }
            try {
                schemas.put(table, queryForMaps(connection, "SHOW CREATE TABLE " + quoteIdentifierPath(table)));
            }
            catch (SQLException ex) {
                schemas.put(table, "Failed to read schema: " + ex.getMessage());
            }
        }
        return schemas;
    }

    /**
     * 收集索引信息
     * <p>
     * 对每个涉及的表执行 SHOW INDEX FROM 命令获取索引详情
     *
     * @param connection 数据库连接
     * @param tables     表名集合
     * @return 索引信息 Map，key 为表名，value 为索引列表或错误信息
     */
    private Map<String, Object> collectIndexes(Connection connection, Set<String> tables) {
        Map<String, Object> indexes = new LinkedHashMap<>();
        for (String table : tables) {
            // 安全检查：跳过不安全的表标识符
            if (!isSafeIdentifierPath(table)) {
                indexes.put(table, "Skipped unsafe table identifier.");
                continue;
            }
            try {
                indexes.put(table, queryForMaps(connection, "SHOW INDEX FROM " + quoteIdentifierPath(table)));
            }
            catch (SQLException ex) {
                indexes.put(table, "Failed to read indexes: " + ex.getMessage());
            }
        }
        return indexes;
    }

    /**
     * 将 EXPLAIN 结果转换为标准化的事实数据格式
     * <p>
     * 统一字段命名，便于 LLM 理解和分析
     *
     * @param explainRows 原始 EXPLAIN 结果行列表
     * @return 标准化后的执行计划事实列表
     */
    private List<Map<String, Object>> explainFacts(List<Map<String, Object>> explainRows) {
        List<Map<String, Object>> facts = new ArrayList<>();
        int rowIndex = 0;
        for (Map<String, Object> row : explainRows) {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("rowIndex", rowIndex++);
            fact.put("id", row.get("id"));
            // 兼容不同版本的 MySQL 返回字段名
            fact.put("selectType", firstExisting(row, "select_type", "selectType"));
            fact.put("table", row.get("table"));
            fact.put("partitions", row.get("partitions"));
            fact.put("accessType", row.get("type"));  // 访问类型（ALL/index/range/ref/const等）
            fact.put("possibleKeys", row.get("possible_keys"));
            fact.put("usedKey", row.get("key"));  // 实际使用的索引
            fact.put("keyLength", row.get("key_len"));
            fact.put("ref", row.get("ref"));
            fact.put("estimatedRows", row.get("rows"));  // 估计扫描行数
            fact.put("filtered", row.get("filtered"));  // 过滤百分比
            fact.put("extra", firstExisting(row, "Extra", "extra"));  // 额外信息
            fact.put("raw", row);  // 保留原始数据
            facts.add(fact);
        }
        return facts;
    }

    /**
     * 获取 Map 中第一个存在的键对应的值
     * <p>
     * 用于兼容不同版本的 MySQL 返回的不同字段命名风格
     *
     * @param row       数据行 Map
     * @param firstKey  优先查找的键
     * @param secondKey 备选键
     * @return 第一个非 null 的值
     */
    private Object firstExisting(Map<String, Object> row, String firstKey, String secondKey) {
        Object first = row.get(firstKey);
        return first != null ? first : row.get(secondKey);
    }

    /**
     * 执行 SQL 查询并将结果转换为 Map 列表
     * <p>
     * 通用工具方法，将 ResultSet 转换为 List<Map<String, Object>> 格式
     *
     * @param connection 数据库连接
     * @param sql        要执行的 SQL 语句
     * @return 查询结果，每行数据为一个 Map，key 为列名，value 为列值
     * @throws SQLException 数据库操作异常
     */
    private List<Map<String, Object>> queryForMaps(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                // 遍历每一列，使用列标签作为 key
                for (int index = 1; index <= columnCount; index++) {
                    row.put(metaData.getColumnLabel(index), rs.getObject(index));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    /**
     * 执行标量查询，返回单个值
     * <p>
     * 适用于 SELECT CURRENT_USER()、SELECT VERSION() 等返回单值的查询
     *
     * @param connection 数据库连接
     * @param sql        查询 SQL
     * @return 第一行第一列的值，如果没有结果则返回 null
     * @throws SQLException 数据库操作异常
     */
    private Object scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    /**
     * 计算慢查询记录的获取数量限制
     * <p>
     * 确保返回值在有效范围内（1 到 maxLimit 之间）
     *
     * @param request SQL 调优请求
     * @return 合法的记录数量限制值
     */
    private int limit(SqlTuningRequest request) {
        // 优先使用请求中的 limit，无效时使用默认值
        int value = request.getLimit() == null || request.getLimit() <= 0 ? properties.getDefaultLimit() : request.getLimit();
        // 限制在 [1, maxLimit] 范围内
        return Math.min(Math.max(value, 1), Math.max(properties.getMaxLimit(), 1));
    }

    /**
     * 生成 SQL 指纹
     * <p>
     * 将 SQL 中的具体值替换为占位符，用于识别结构相同的 SQL
     * 例如：SELECT * FROM users WHERE id = 1 和 SELECT * FROM users WHERE id = 2 会生成相同的指纹
     *
     * @param sql 原始 SQL 语句
     * @return 标准化后的 SQL 指纹（小写、去除多余空格、字面量替换为 ?）
     */
    private String fingerprint(String sql) {
        if (sql == null) {
            return "";
        }
        // 替换字符串字面量、数字、规范化空格，并转为小写
        return sql.replaceAll("'([^'\\\\]|\\\\.)*'", "?")
                .replaceAll("\"([^\"\\\\]|\\\\.)*\"", "?")
                .replaceAll("\\b\\d+(?:\\.\\d+)?\\b", "?")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 检查 SQL 是否是安全的 EXPLAIN 目标
     * <p>
     * 仅允许对 SELECT 或 WITH 语句执行 EXPLAIN，防止执行危险操作
     *
     * @param sql 待检查的 SQL 语句
     * @return true 表示可以安全执行 EXPLAIN，false 表示不安全
     */
    private boolean isSafeExplainSelect(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        // 只允许 SELECT 和 WITH（CTE）语句
        if (!(normalized.startsWith("select") || normalized.startsWith("with"))) {
            return false;
        }
        // 排除包含危险操作的 SQL
        return !normalized.contains(";")
                && !normalized.contains(" into outfile")
                && !normalized.contains(" benchmark(")
                && !normalized.contains(" sleep(")
                && !normalized.contains(" load_file(");
    }

    /**
     * 从 SQL 语句中提取涉及的表名
     * <p>
     * 使用正则表达式匹配 FROM 和 JOIN 关键字后的表名
     *
     * @param sql SQL 语句
     * @return 表名集合（去重）
     */
    private Set<String> extractTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            // 去除反引号，保留纯表名
            tables.add(matcher.group(1).replace("`", ""));
        }
        return tables;
    }

    /**
     * 检查标识符路径是否安全
     * <p>
     * 防止 SQL 注入攻击，只允许包含字母、数字、下划线、美元符号、点和反引号的标识符
     *
     * @param value 待检查的标识符
     * @return true 表示安全，false 表示不安全
     */
    private boolean isSafeIdentifierPath(String value) {
        return value != null && value.matches("[A-Za-z0-9_$.`]+") && !value.contains(";");
    }

    /**
     * 为标识符路径添加反引号引用
     * <p>
     * 处理数据库名.表名的形式，为每个部分单独添加反引号
     * 例如：db.table → `db`.`table`
     *
     * @param value 原始标识符路径
     * @return 添加反引号后的标识符路径
     */
    private String quoteIdentifierPath(String value) {
        // 先去除已有的反引号
        String normalized = value.replace("`", "");
        String[] parts = normalized.split("\\.");
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            quoted.add("`" + part + "`");
        }
        return String.join(".", quoted);
    }

    /**
     * 构建启用慢查询计划的报告
     * <p>
     * 生成 Markdown 格式的报告，展示需要执行的命令和风险提示
     *
     * @param response SQL 调优响应对象
     * @return Markdown 格式的报告字符串
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
     * 构建慢查询已启用的结果报告
     *
     * @param response SQL 调优响应对象
     * @return Markdown 格式的报告字符串
     */
    private String buildEnableResultReport(SqlTuningResponse response) {
        return "## 慢查询已开启\n\n已执行用户确认的 SET GLOBAL 命令。请等待业务流量产生慢 SQL 后再次运行调优 Workflow。";
    }

    /**
     * 构建不支持的日志输出方式的报告
     *
     * @param response SQL 调优响应对象
     * @return Markdown 格式的报告字符串
     */
    private String buildUnsupportedLogOutputReport(SqlTuningResponse response) {
        return "## 暂不支持当前慢日志输出方式\n\n慢查询已开启，但 log_output 不是 TABLE。第一版只读取 mysql.slow_log。\n\n当前配置："
                + response.getSlowQueryConfig();
    }

    /**
     * 构建暂无慢查询的报告
     *
     * @param response SQL 调优响应对象
     * @return Markdown 格式的报告字符串
     */
    private String buildNoSlowSqlReport(SqlTuningResponse response) {
        return "## 暂无慢 SQL\n\n慢查询已开启，但 mysql.slow_log 当前没有可分析记录。可能原因：刚开启、业务流量不足、long_query_time 阈值过高。";
    }

    /**
     * 构建完整的 SQL 调优分析报告
     * <p>
     * 遍历所有分析的 SQL，整合 LLM 分析结果生成 Markdown 格式的详细报告
     *
     * @param response SQL 调优响应对象，包含分析结果列表
     * @return Markdown 格式的分析报告
     */
    private String buildAnalysisReport(SqlTuningResponse response) {
        StringBuilder builder = new StringBuilder();
        builder.append("## SQL 调优分析结果\n\n");
        for (Map<String, Object> analysis : response.getAnalyses()) {
            // 显示示例 SQL
            builder.append("### SQL\n\n```sql\n").append(analysis.get("sampleSql")).append("\n```\n\n");
            Object llm = analysis.get("llmAnalysis");
            if (llm instanceof SqlTuningLlmAnalysisResult result) {
                Map<String, Object> modelAnalysis = result.getAnalysis();
                // 输出 LLM 分析的各个部分
                builder.append("模型状态：").append(result.getStatus()).append("\n\n");
                builder.append("总结：").append(modelAnalysis.get("summary")).append("\n\n");
                builder.append("建议：").append(modelAnalysis.get("recommendations")).append("\n\n");
                builder.append("风险：").append(modelAnalysis.get("risks")).append("\n\n");
                builder.append("验证：").append(modelAnalysis.get("verificationPlan")).append("\n\n");
            }
            else {
                builder.append("模型分析：未执行。状态：").append(analysis.get("status")).append("\n\n");
            }
        }
        return builder.toString();
    }

    /**
     * 将对象转换为字符串列表
     *
     * @param value 待转换的对象
     * @return 字符串列表，如果对象不是 List 类型则返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * 获取第一个非空白字符串
     *
     * @param first  优先检查的字符串
     * @param second 备选字符串
     * @return 第一个非空白字符串，如果都为空则返回第二个参数
     */
    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    /**
     * 判断字符串是否为空或空白
     *
     * @param value 待检查的字符串
     * @return true 表示为空或 null，false 表示有内容
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 解析 MySQL 时间格式字符串为秒数
     * <p>
     * 支持 HH:MM:SS 格式和纯数字格式
     *
     * @param mysqlTime MySQL 时间字符串（如 "00:00:05" 或 "5.123"）
     * @return 转换后的秒数，解析失败返回 0
     */
    private double parseSeconds(String mysqlTime) {
        if (mysqlTime == null || mysqlTime.isBlank()) {
            return 0;
        }
        String[] parts = mysqlTime.split(":");
        try {
            // 处理 HH:MM:SS 格式
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600D + Integer.parseInt(parts[1]) * 60D + Double.parseDouble(parts[2]);
            }
            // 处理纯数字格式
            return Double.parseDouble(mysqlTime);
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * 数据库连接信息记录
     * <p>
     * 封装连接 MySQL 所需的所有参数，并提供验证和安全展示功能
     *
     * @param host        主机地址
     * @param port        端口号
     * @param database    数据库名
     * @param username    用户名
     * @param password    密码
     * @param environment 环境标识
     */
    private record ConnectionInfo(String host, int port, String database, String username, String password,
            String environment) {

        /**
         * 检查缺失的必填字段
         *
         * @return 缺失字段名称列表
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
         * 生成安全的连接信息 Map
         * <p>
         * 不直接暴露密码，仅显示密码是否已配置
         *
         * @return 安全的连接信息 Map
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
     * 慢查询记录
     * <p>
     * 封装从 mysql.slow_log 表中读取的单条慢查询记录
     *
     * @param startTime      开始执行时间
     * @param userHost       用户和主机信息
     * @param queryTime      查询耗时
     * @param lockTime       锁等待时间
     * @param rowsSent       发送的行数
     * @param rowsExamined   检查的行数
     * @param db             数据库名
     * @param sqlText        SQL 语句文本
     */
    private record SlowSqlRecord(String startTime, String userHost, String queryTime, String lockTime, long rowsSent,
            long rowsExamined, String db, String sqlText) {
    }

    /**
     * SQL 分析数据包
     * <p>
     * 封装单个慢查询的完整分析数据，包括原始数据、LLM 上下文等
     *
     * @param group      慢查询组
     * @param status     分析状态
     * @param message    状态消息
     * @param rawData    原始数据（表结构、索引、执行计划等）
     * @param llmContext LLM 分析上下文
     */
    private record SqlAnalysisBundle(SlowSqlGroup group, String status, String message, Map<String, Object> rawData,
            SqlTuningAnalysisContext llmContext) {
    }

    /**
     * 慢查询组
     * <p>
     * 将具有相同指纹（结构相同）的慢查询记录归为一组，统计聚合信息并计算优先级分数
     */
    private class SlowSqlGroup {

        /** SQL 指纹（标准化后的 SQL 模板） */
        private final String fingerprint;

        /** 示例 SQL（该组的代表性 SQL 语句） */
        private final String sampleSql;

        /** 数据库名 */
        private final String database;

        /** 出现次数 */
        private int count;

        /** 总查询时间（秒） */
        private double totalQueryTimeSeconds;

        /** 最大查询时间（秒） */
        private double maxQueryTimeSeconds;

        /** 总检查行数 */
        private long totalRowsExamined;

        /** 最大检查行数 */
        private long maxRowsExamined;

        /** 总发送行数 */
        private long totalRowsSent;

        /**
         * 构造函数
         *
         * @param fingerprint SQL 指纹
         * @param sampleSql   示例 SQL
         * @param database    数据库名
         */
        SlowSqlGroup(String fingerprint, String sampleSql, String database) {
            this.fingerprint = fingerprint;
            this.sampleSql = sampleSql;
            this.database = database;
        }

        /**
         * 添加一条慢查询记录到该组
         * <p>
         * 累加统计数据：次数、总时间、最大时间、检查行数、发送行数
         *
         * @param record 慢查询记录
         */
        void add(SlowSqlRecord record) {
            count++;
            // 解析并累加查询时间
            double querySeconds = parseSeconds(record.queryTime());
            totalQueryTimeSeconds += querySeconds;
            maxQueryTimeSeconds = Math.max(maxQueryTimeSeconds, querySeconds);
            // 累加检查行数
            totalRowsExamined += record.rowsExamined();
            maxRowsExamined = Math.max(maxRowsExamined, record.rowsExamined());
            // 累加发送行数
            totalRowsSent += record.rowsSent();
        }

        /**
         * 计算优先级分数
         * <p>
         * 评分公式：次数 × 平均查询时间 + 最大查询时间 + 平均检查行数 / 10000
         * 综合考虑了频率、耗时和扫描数据量
         *
         * @return 优先级分数，分数越高越需要优化
         */
        double priorityScore() {
            return count * avgQueryTimeSeconds() + maxQueryTimeSeconds + avgRowsExamined() / 10000D;
        }

        /**
         * 计算平均查询时间
         *
         * @return 平均查询时间（秒）
         */
        double avgQueryTimeSeconds() {
            return count == 0 ? 0 : totalQueryTimeSeconds / count;
        }

        /**
         * 计算平均检查行数
         *
         * @return 平均检查行数
         */
        long avgRowsExamined() {
            return count == 0 ? 0 : totalRowsExamined / count;
        }

        /**
         * 计算平均发送行数
         *
         * @return 平均发送行数
         */
        long avgRowsSent() {
            return count == 0 ? 0 : totalRowsSent / count;
        }

        /**
         * 获取 SQL 指纹
         *
         * @return SQL 指纹字符串
         */
        String fingerprint() {
            return fingerprint;
        }

        /**
         * 获取示例 SQL
         *
         * @return 示例 SQL 语句
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
         * 将慢查询组转换为 Map
         * <p>
         * 包含所有统计指标和优先级分数，用于响应输出
         *
         * @return 包含完整统计信息的 Map
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