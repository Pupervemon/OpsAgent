package com.test.opsagent.agent.tool.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.test.opsagent.agent.service.VectorSearchService;
import com.test.opsagent.agent.tool.ops.OpsJson;
import com.test.opsagent.agent.tool.ops.OpsToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 运维知识库查询工具。
 * <p>
 * 这里复用现有向量检索服务。当前运维文档还没补充时，可能返回空结果；
 * Milvus、Embedding 或索引不可用时会返回 unavailable，Agent 需要如实说明。
 */
@Component
public class KnowledgeSearchTools {

    private final VectorSearchService vectorSearchService;

    public KnowledgeSearchTools(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * 查询运维知识库。topK 做上限保护，避免一次返回过多片段。
     */
    @Tool(description = "Search project operations knowledge base. Returns empty results if no matching documents have been indexed.")
    public String searchOperationsKnowledge(
            @ToolParam(description = "Question or keyword to search in the operations knowledge base") String query,
            @ToolParam(description = "Maximum number of results to return, usually 3 to 5") int topK) {
        if (query == null || query.isBlank()) {
            return OpsJson.stringify(OpsToolResult.failed("searchOperationsKnowledge", "Query is required"));
        }

        int limit = topK <= 0 ? 3 : Math.min(topK, 10);
        try {
            List<VectorSearchService.SearchResult> results = vectorSearchService.searchSimilarDocuments(query, limit);
            List<Map<String, Object>> data = results.stream()
                    .map(result -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", result.getId());
                        item.put("score", result.getScore());
                        item.put("content", result.getContent());
                        item.put("metadata", result.getMetadata());
                        return item;
                    })
                    .toList();

            return OpsJson.stringify(OpsToolResult.ok("searchOperationsKnowledge",
                    data.isEmpty()
                            ? "Knowledge base is available but no matching operations documents were found."
                            : "Found matching operations knowledge documents.",
                    data));
        }
        catch (Exception ex) {
            return OpsJson.stringify(OpsToolResult.unavailable("searchOperationsKnowledge",
                    "Knowledge search failed or vector dependencies are unavailable: " + ex.getMessage()));
        }
    }
}
