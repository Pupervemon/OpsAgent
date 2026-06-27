package com.test.opsagent.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文档分片配置
 */
@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "document.chunk")
public class DocumentChunkConfig {
    
    /**
     * 每个分片的最大字符数
     */
    private int maxSize = 800;
    
    /**
     * 分片之间的重叠字符数
     */
    private int overlap = 100;

}
