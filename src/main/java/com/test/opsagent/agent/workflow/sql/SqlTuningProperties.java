package com.test.opsagent.agent.workflow.sql;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SQL 调优 Workflow 的默认配置。
 * <p>
 * 连接信息可以通过 application.yml 或环境变量提供；请求体中也可以临时覆盖。
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "agent.sql-tuning")
public class SqlTuningProperties {

    private String host;

    private int port = 3306;

    private String database;

    private String username;

    private String password;

    private int defaultPort = 3306;

    private int defaultLimit = 50;

    private int maxLimit = 50;

    private int defaultLongQueryTimeSeconds = 1;

    private int defaultMinExaminedRowLimit = 100;

    private String defaultLogOutput = "TABLE";

    private int connectTimeoutSeconds = 5;

    private int socketTimeoutSeconds = 10;

}