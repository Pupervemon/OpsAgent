package com.test.opsagent.agent.tool.ops;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 服务器状态查询工具。
 * <p>
 * 优先使用 JVM 和文件系统 API 获取信息，不依赖 Linux 命令。
 * 这样即使目标服务器缺少某些命令，也能返回基础的 CPU、内存、磁盘和 JVM 状态。
 */
@Component
public class ServerStatusTools {

    /**
     * 获取服务器基础概览。该方法只读，不改变服务器状态。
     */
    @Tool(description = "Get a read-only server overview including CPU, memory, disk roots, uptime, OS, and JVM information.")
    public String getServerOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("os", osInfo());
        data.put("jvm", jvmInfo());
        data.put("memory", memoryInfo());
        data.put("diskRoots", diskRoots());

        return OpsJson.stringify(OpsToolResult.ok("getServerOverview",
                "Collected server overview from JVM and filesystem APIs.", data));
    }

    private Map<String, Object> osInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", System.getProperty("os.name"));
        data.put("version", System.getProperty("os.version"));
        data.put("arch", System.getProperty("os.arch"));
        data.put("availableProcessors", Runtime.getRuntime().availableProcessors());

        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        data.put("systemLoadAverage", bean.getSystemLoadAverage());
        // com.sun.management.OperatingSystemMXBean 是 HotSpot/OpenJDK 常见扩展；
        // 不可用时仍保留标准 OperatingSystemMXBean 的基础信息。
        if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            data.put("processCpuLoad", sunBean.getProcessCpuLoad());
            data.put("systemCpuLoad", sunBean.getCpuLoad());
            data.put("totalMemoryBytes", sunBean.getTotalMemorySize());
            data.put("freeMemoryBytes", sunBean.getFreeMemorySize());
        }
        return data;
    }

    private Map<String, Object> jvmInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        data.put("startTimeMs", ManagementFactory.getRuntimeMXBean().getStartTime());
        return data;
    }

    private Map<String, Object> memoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jvmMaxBytes", runtime.maxMemory());
        data.put("jvmTotalBytes", runtime.totalMemory());
        data.put("jvmFreeBytes", runtime.freeMemory());
        data.put("jvmUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        return data;
    }

    private List<Map<String, Object>> diskRoots() {
        List<Map<String, Object>> roots = new ArrayList<>();
        File[] files = File.listRoots();
        if (files == null) {
            return roots;
        }
        for (File root : files) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", root.getAbsolutePath());
            data.put("totalBytes", root.getTotalSpace());
            data.put("freeBytes", root.getFreeSpace());
            data.put("usableBytes", root.getUsableSpace());
            roots.add(data);
        }
        return roots;
    }
}
