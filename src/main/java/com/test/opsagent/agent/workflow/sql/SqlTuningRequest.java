package com.test.opsagent.agent.workflow.sql;

import lombok.Getter;
import lombok.Setter;

/**
 * SQL 调优请求。
 * <p>
 * 第一版支持从请求中临时覆盖连接信息；缺失字段会回退到配置文件。
 */
@Setter
@Getter
public class SqlTuningRequest {

    private String host;

    private Integer port;

    private String database;

    private String username;

    private String password;

    private String environment;

    private Integer limit;

    private Boolean approveEnableSlowQuery;

    private Integer longQueryTimeSeconds;

    private Integer minExaminedRowLimit;

    private String logOutput;

}