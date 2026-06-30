package com.test.opsagent.agent.api;

import com.test.opsagent.agent.workflow.sql.SqlTuningRequest;
import com.test.opsagent.agent.workflow.sql.SqlTuningResponse;
import com.test.opsagent.agent.workflow.sql.SqlTuningWorkflowService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SQL 调优 Workflow HTTP 入口。
 * <p>
 * 第一版使用同步 JSON 接口，后续如果分析耗时变长，可以扩展为任务式接口或 SSE 进度推送。
 */
@RestController
@RequestMapping("/api/sql-tuning")
public class SqlTuningController {

    private final SqlTuningWorkflowService workflowService;

    public SqlTuningController(SqlTuningWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping(path = "/run", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SqlTuningResponse run(@RequestBody(required = false) SqlTuningRequest request) {
        return workflowService.run(request == null ? new SqlTuningRequest() : request);
    }
}