# SQL 调优 Workflow 设计与初版计划

## 1. 目标

SQL 调优 Workflow 用于从目标 MySQL 的慢查询中发现高价值问题 SQL，自动采集结构化证据，分析执行计划，并输出可验证的优化建议。

第一版定位为只读诊断和建议生成，不自动创建索引、不自动改表、不自动执行真实业务 SQL。

核心目标：

- 检查 MySQL 是否可连接。
- 检查慢查询是否开启。
- 慢查询未开启时，生成开启方案并等待用户确认。
- 慢查询已开启时，读取最近慢 SQL。
- 对慢 SQL 做归一化、聚合和排序。
- 对重点 SELECT SQL 执行 EXPLAIN。
- 分析执行计划并输出 SQL 改写建议、索引建议、风险评估和验证方案。

## 2. 安全边界

允许操作：

- 测试 MySQL 连接。
- 查询 MySQL 系统变量。
- 查询慢查询配置。
- 读取 `mysql.slow_log`。
- 查询表结构和索引信息。
- 执行 `EXPLAIN SELECT`。
- 生成优化建议和调优报告。

禁止操作：

- 自动执行 `CREATE INDEX`。
- 自动执行 `ALTER TABLE`。
- 自动执行 `UPDATE`、`DELETE`、`INSERT`。
- 自动执行真实慢 SQL。
- 自动执行 `EXPLAIN ANALYZE`。
- 自动修改生产配置。

需要用户确认的操作：

- 开启慢查询。
- 修改 `long_query_time`。
- 修改 `log_output`。
- 后续可能扩展的 `ANALYZE TABLE`、DDL、索引创建等操作。

敏感信息规则：

- 数据库密码不进入 prompt。
- 数据库密码不写入日志。
- 数据库密码不进入会话记忆。
- 审计记录中只记录连接目标和操作类型，不记录明文密码。

## 3. 总体流程

```text
Start
  |
  v
读取 MySQL 连接配置
  |
  v
连接测试
  |
  +-- 失败 --> 输出连接失败原因 --> End
  |
  v
检查慢查询配置
  |
  +-- 未开启 --> 生成开启方案 --> 用户确认？
  |                                  |
  |                                  +-- 否 --> 输出配置建议 --> End
  |                                  |
  |                                  +-- 是 --> 执行 SET GLOBAL --> 重新检查配置 --> End
  |
  v
读取最近 50 条慢 SQL
  |
  +-- 无数据 --> 提示暂无慢 SQL / 阈值可能过高 --> End
  |
  v
SQL 指纹聚合
  |
  v
Top SQL 排名
  |
  v
收集表结构和索引
  |
  v
执行 EXPLAIN
  |
  v
分析执行计划
  |
  v
生成优化建议
  |
  v
风险评估
  |
  v
生成验证方案
  |
  v
输出调优报告
  |
  v
End
```

## 4. Workflow 节点设计

### 4.1 MysqlConnectionConfigNode

职责：确认是否具备连接 MySQL 所需配置。

需要字段：

- `host`
- `port`
- `database`
- `username`
- `password`
- `environment`

配置来源优先级：

1. 系统配置。
2. 环境变量。
3. 密钥管理。
4. 用户临时输入。

如果缺少配置，Workflow 应提示用户需要配置连接信息，而不是继续执行。

### 4.2 MysqlConnectionCheckNode

职责：尝试连接目标 MySQL，并识别失败原因。

需要区分的失败类型：

- 网络不可达。
- 端口不通。
- 账号密码错误。
- 数据库不存在。
- 权限不足。
- SSL 或认证方式不兼容。
- 未知错误。

输出：

- `connectionStatus`
- `mysqlVersion`
- `currentUser`
- `currentDatabase`
- `errorMessage`

### 4.3 SlowQueryConfigCheckNode

职责：检查慢查询是否开启，以及当前配置是否合理。

需要查询：

```sql
SHOW VARIABLES LIKE 'slow_query_log';
SHOW VARIABLES LIKE 'long_query_time';
SHOW VARIABLES LIKE 'log_output';
SHOW VARIABLES LIKE 'slow_query_log_file';
SHOW VARIABLES LIKE 'min_examined_row_limit';
SHOW VARIABLES LIKE 'log_queries_not_using_indexes';
```

判断点：

- `slow_query_log` 是否为 `ON`。
- `long_query_time` 是否过高。
- `log_output` 是 `TABLE` 还是 `FILE`。
- 是否能直接查询 `mysql.slow_log`。

### 4.4 SlowQueryEnablePlanNode

职责：慢查询未开启时，生成开启方案。

第一版建议使用临时开启：

```sql
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;
SET GLOBAL log_output = 'TABLE';
SET GLOBAL min_examined_row_limit = 100;
```

说明：

- `SET GLOBAL` 重启后失效。
- `log_output = TABLE` 方便系统直接查询 `mysql.slow_log`。
- `long_query_time = 1` 适合作为初步排查阈值。
- 生产环境需要评估日志增长。

该节点只生成方案，不执行命令。

### 4.5 UserApprovalNode

职责：对所有会改变数据库状态的操作进行人工确认。

第一版需要确认的动作：

- 开启慢查询。
- 修改慢查询阈值。
- 修改慢查询输出方式。

确认信息需要包含：

- 即将执行的命令。
- 影响范围。
- 风险说明。
- 是否可回滚。

### 4.6 SlowQueryEnableExecuteNode

职责：用户确认后执行开启慢查询命令。

执行前检查：

- 当前账号是否有修改全局变量的权限。
- 当前 MySQL 版本是否支持相关变量。

执行后检查：

- `slow_query_log` 是否已变为 `ON`。
- `long_query_time` 是否生效。
- `log_output` 是否生效。

如果失败，需要返回明确原因，例如权限不足、云数据库限制、只读实例限制等。

### 4.7 SlowSqlFetchNode

职责：读取最近慢 SQL。

优先支持 `log_output = TABLE`：

```sql
SELECT
  start_time,
  user_host,
  query_time,
  lock_time,
  rows_sent,
  rows_examined,
  db,
  sql_text
FROM mysql.slow_log
ORDER BY start_time DESC
LIMIT 50;
```

如果 `log_output = FILE`：

- 第一版不直接读取文件。
- 返回慢日志文件路径。
- 提示需要配置文件读取工具，或将 `log_output` 切换为 `TABLE`。

### 4.8 SlowSqlFingerprintNode

职责：将慢 SQL 归一化，聚合同类 SQL。

示例：

```sql
select * from orders where user_id = 1;
select * from orders where user_id = 2;
```

归一化为：

```sql
select * from orders where user_id = ?;
```

聚合字段：

- SQL 指纹。
- 样例 SQL。
- 出现次数。
- 平均耗时。
- 最大耗时。
- 平均扫描行数。
- 最大扫描行数。
- 平均返回行数。

### 4.9 SlowSqlRankNode

职责：从慢 SQL 中选出优先分析对象。

排序依据：

- 出现次数。
- 平均耗时。
- 最大耗时。
- `rows_examined`。
- `rows_examined / rows_sent`。
- `lock_time`。

第一版可以输出 Top 5 SQL 模板。

### 4.10 SlowSqlClassifyNode

职责：对慢 SQL 做初步分类。

分类类型：

- `NO_INDEX`
- `BAD_INDEX`
- `FULL_TABLE_SCAN`
- `FILESORT`
- `TEMP_TABLE`
- `JOIN_WITHOUT_INDEX`
- `TOO_MANY_ROWS_EXAMINED`
- `LOCK_WAIT`
- `DEEP_PAGINATION`
- `SELECT_TOO_MANY_COLUMNS`
- `FUNCTION_ON_INDEX_COLUMN`
- `IMPLICIT_TYPE_CONVERSION`
- `UNKNOWN`

注意：没有索引不一定代表应该加索引。还要判断表大小、字段选择性、查询频率、写入压力和已有索引情况。

### 4.11 SchemaAndIndexCollectNode

职责：针对候选 SQL 涉及的表采集结构和索引。

查询：

```sql
SHOW CREATE TABLE table_name;
SHOW INDEX FROM table_name;
```

可选查询：

```sql
SELECT
  TABLE_NAME,
  TABLE_ROWS,
  DATA_LENGTH,
  INDEX_LENGTH
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = ?
  AND TABLE_NAME IN (...);
```

原则：

- 只查 SQL 涉及的表。
- 不扫全库。
- 不采集真实业务数据。

### 4.12 ExplainExecuteNode

职责：对候选 SELECT SQL 执行 EXPLAIN。

优先执行：

```sql
EXPLAIN FORMAT=JSON <sql>;
```

如果不支持，则降级：

```sql
EXPLAIN <sql>;
```

第一版只支持：

- `SELECT`
- `WITH ... SELECT`

第一版不支持：

- `UPDATE`
- `DELETE`
- `INSERT`
- `EXPLAIN ANALYZE`

### 4.13 ExplainAnalyzeNode

职责：分析执行计划。

重点分析：

- 是否全表扫描。
- 是否命中索引。
- `possible_keys` 是否有候选索引。
- `key` 是否为空。
- `rows` 是否过大。
- `filtered` 是否过低。
- 是否出现 `Using filesort`。
- 是否出现 `Using temporary`。
- JOIN 顺序是否异常。
- 是否存在回表成本。

### 4.14 OptimizationSuggestNode

职责：生成优化建议。

建议类型：

- SQL 改写建议。
- 索引建议。
- 统计信息建议。
- 业务侧建议。

SQL 改写建议示例：

- 避免 `SELECT *`。
- 避免在索引字段上使用函数。
- 避免隐式类型转换。
- 将部分 `OR` 改写为 `UNION ALL`。
- 深分页改为游标分页。
- 增加合理时间范围条件。

索引建议示例：

```sql
-- 建议，不自动执行
CREATE INDEX idx_orders_user_time ON orders(user_id, create_time);
```

索引建议必须说明依据和风险。

### 4.15 RiskAssessNode

职责：评估建议风险。

索引风险：

- 增加写入成本。
- 增加磁盘占用。
- 大表建索引可能锁表。
- 可能和已有索引重复。
- 可能影响优化器选择。

SQL 改写风险：

- 返回结果是否一致。
- 排序语义是否变化。
- 分页语义是否变化。
- `NULL` 处理是否变化。

配置风险：

- 慢日志量增加。
- `mysql.slow_log` 表增长。
- 需要定期清理慢日志。

风险等级：

- `LOW`
- `MEDIUM`
- `HIGH`

### 4.16 VerificationPlanNode

职责：生成验证方案。

验证内容：

- 优化前 EXPLAIN。
- 优化后 EXPLAIN。
- 对比 `rows`。
- 对比 `key`。
- 对比 `Extra`。
- 测试环境执行采样 SQL。
- 对比耗时。
- 对比结果集一致性。
- 上线后观察慢查询是否下降。

### 4.17 ReportGenerateNode

职责：生成最终调优报告。

报告结构：

1. 连接与环境信息。
2. 慢查询配置状态。
3. 慢 SQL 概览。
4. Top 慢 SQL 排名。
5. 重点 SQL 分析。
6. 执行计划分析。
7. 问题归因。
8. 优化建议。
9. 风险评估。
10. 验证方案。
11. 需要用户确认的后续动作。

## 5. Workflow 上下文数据

建议维护一个贯穿全流程的上下文对象。

字段设计：

- `requestId`
- `userId`
- `environment`
- `mysqlConnectionInfo`
- `connectionStatus`
- `slowQueryConfig`
- `enablePlan`
- `approvalStatus`
- `slowSqlRecords`
- `slowSqlFingerprints`
- `rankedSlowSql`
- `selectedSql`
- `tableSchemas`
- `indexes`
- `explainResults`
- `diagnosis`
- `suggestions`
- `risks`
- `verificationPlan`
- `report`

这样每个节点输入输出清晰，也方便前端展示进度和后续审计。

## 6. Agent 与 Workflow 的关系

该能力应该以 Workflow 为主，不应该让 ReActAgent 自由决定每一步。

职责划分：

- Workflow 控制步骤。
- 工具执行确定性 SQL 查询。
- LLM 参与解释执行计划、生成建议和报告。
- 用户确认高风险动作。

适合 LLM 的节点：

- `SlowSqlClassifyNode`
- `ExplainAnalyzeNode`
- `OptimizationSuggestNode`
- `RiskAssessNode`
- `ReportGenerateNode`

不适合 LLM 自由控制的节点：

- 连接数据库。
- 执行 `SET GLOBAL`。
- 查询 `mysql.slow_log`。
- 查询表结构。
- 执行 `EXPLAIN`。

这些节点应由固定代码控制。

## 7. 第一版 MVP 范围

第一版实现：

- 配置读取 MySQL 连接。
- 测试连接。
- 检查慢查询是否开启。
- 未开启时输出开启建议。
- 用户确认后临时开启慢查询。
- 已开启时读取最近 50 条 `mysql.slow_log`。
- SQL 指纹聚合。
- Top 5 慢 SQL 排名。
- 对 SELECT 执行 EXPLAIN。
- 输出调优报告。

第一版暂不实现：

- 自动建索引。
- 自动执行 `ANALYZE TABLE`。
- 读取 FILE 类型慢日志。
- 分析 `UPDATE` / `DELETE`。
- `EXPLAIN ANALYZE`。
- 生产自动 DDL。

## 8. 后续扩展方向

后续可以扩展：

- 慢 SQL 日志批量分析。
- Performance Schema / sys schema 分析。
- 冗余索引分析。
- 索引变更审批单生成。
- 测试库自动验证索引收益。
- 生产 DDL 前人工审批。
- 与发布系统联动做上线后验证。

## 9. 核心原则

- Workflow 决定步骤，不让模型自由执行数据库动作。
- 所有状态变更必须人工确认。
- 第一版只读优先。
- 所有建议必须有证据。
- 所有索引建议必须有风险说明。
- 所有优化建议必须有验证方案。