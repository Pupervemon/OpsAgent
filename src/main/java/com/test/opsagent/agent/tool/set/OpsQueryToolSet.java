package com.test.opsagent.agent.tool.set;

import java.util.Set;

import com.test.opsagent.agent.runtime.AgentScene;
import com.test.opsagent.agent.runtime.AgentToolSet;
import com.test.opsagent.agent.tool.DateTimeTools;
import com.test.opsagent.agent.tool.knowledge.KnowledgeSearchTools;
import com.test.opsagent.agent.tool.ops.AppVersionTools;
import com.test.opsagent.agent.tool.ops.LogQueryTools;
import com.test.opsagent.agent.tool.ops.ServerStatusTools;
import com.test.opsagent.agent.tool.ops.ServiceQueryTools;
import org.springframework.stereotype.Component;

/**
 * 运维自由查询 Agent 的工具集合。
 * <p>
 * 这里集中声明 OPS_QUERY 场景可用的只读工具。
 * 后续新增会改变服务器状态的工具时，不要放到这个工具集中，应放到 Workflow。
 */
@Component
public class OpsQueryToolSet implements AgentToolSet {

    private final DateTimeTools dateTimeTools;

    private final ServerStatusTools serverStatusTools;

    private final ServiceQueryTools serviceQueryTools;

    private final LogQueryTools logQueryTools;

    private final AppVersionTools appVersionTools;

    private final KnowledgeSearchTools knowledgeSearchTools;

    public OpsQueryToolSet(DateTimeTools dateTimeTools, ServerStatusTools serverStatusTools,
            ServiceQueryTools serviceQueryTools, LogQueryTools logQueryTools, AppVersionTools appVersionTools,
            KnowledgeSearchTools knowledgeSearchTools) {
        this.dateTimeTools = dateTimeTools;
        this.serverStatusTools = serverStatusTools;
        this.serviceQueryTools = serviceQueryTools;
        this.logQueryTools = logQueryTools;
        this.appVersionTools = appVersionTools;
        this.knowledgeSearchTools = knowledgeSearchTools;
    }

    @Override
    public Set<AgentScene> scenes() {
        return Set.of(AgentScene.OPS_QUERY);
    }

    @Override
    public Object[] methodTools() {
        return new Object[] {
                dateTimeTools,
                serverStatusTools,
                serviceQueryTools,
                logQueryTools,
                appVersionTools,
                knowledgeSearchTools
        };
    }

    @Override
    public String prompt() {
        return """
                Tool usage guidance:
                - Use service tools only with names returned by listAllowedServices.
                - If a service, log path, systemctl, git, or knowledge base is unavailable, report that plainly.
                - Do not infer that a service is healthy unless health check or status evidence supports it.
                - For log analysis, quote concise evidence and avoid returning excessive raw logs.
                """;
    }
}
