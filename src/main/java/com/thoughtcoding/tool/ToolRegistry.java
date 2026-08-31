package com.thoughtcoding.tool;

import com.thoughtcoding.config.AppConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负责工具注册、发现和生命周期管理
 *
 * 工具系统的核心管理者，维护了所有可用工具的映射表，并提供统一的调用接口，
 */
public class ToolRegistry {
    public static final String APPLICATION_OWNER = "application";

    private record RegisteredTool(String owner, BaseTool tool) {}

    private final Map<String, RegisteredTool> tools;
    private final AppConfig appConfig;

    public ToolRegistry(AppConfig appConfig) {
        this.tools = new ConcurrentHashMap<>();
        this.appConfig = appConfig;
    }

    // 🔥 统一注册入口，接受 BaseTool（内置工具与 MCP 工具共用）
    public boolean register(BaseTool tool) {
        return register(APPLICATION_OWNER, tool);
    }

    /**
     * 按所有者注册工具。同一所有者可刷新同名工具；不同所有者发生重名时拒绝覆盖，
     * 防止 MCP 工具替换内置工具或另一个服务器的能力。
     */
    public boolean register(String owner, BaseTool tool) {
        if (tool == null || tool.getName() == null || tool.getName().isBlank()
                || owner == null || owner.isBlank() || !isToolEnabled(tool.getName())) {
            return false;
        }

        RegisteredTool incoming = new RegisteredTool(owner, tool);
        RegisteredTool resolved = tools.compute(tool.getName(), (name, existing) -> {
            if (existing == null || existing.owner().equals(owner)) {
                return incoming;
            }
            return existing;
        });
        return resolved == incoming;
    }

    public BaseTool getTool(String toolName) {
        RegisteredTool registered = tools.get(toolName);
        return registered != null ? registered.tool() : null;
    }

    /** 删除某个所有者注册的全部工具，返回实际删除数量。 */
    public int unregisterOwner(String owner) {
        if (owner == null || owner.isBlank()) return 0;
        return unregisterMatching(registered -> owner.equals(registered.owner()));
    }

    /** 删除所有者前缀匹配的工具（用于应用关闭时批量回收 MCP 能力）。 */
    public int unregisterOwnersWithPrefix(String ownerPrefix) {
        if (ownerPrefix == null || ownerPrefix.isBlank()) return 0;
        return unregisterMatching(registered -> registered.owner().startsWith(ownerPrefix));
    }

    public int countOwnersWithPrefix(String ownerPrefix) {
        if (ownerPrefix == null || ownerPrefix.isBlank()) return 0;
        return (int) tools.values().stream()
                .filter(registered -> registered.owner().startsWith(ownerPrefix))
                .count();
    }

    public String ownerOf(String toolName) {
        RegisteredTool registered = tools.get(toolName);
        return registered != null ? registered.owner() : null;
    }

    public int size() {
        return tools.size();
    }

    private int unregisterMatching(java.util.function.Predicate<RegisteredTool> predicate) {
        int removed = 0;
        for (Map.Entry<String, RegisteredTool> entry : tools.entrySet()) {
            RegisteredTool registered = entry.getValue();
            if (predicate.test(registered) && tools.remove(entry.getKey(), registered)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 🔥 把所有已启用工具（内置 + MCP）映射为 langchain4j 原生 ToolSpecification，
     * 在每次请求时调用以纳入运行时连接的 MCP 工具。单个工具转换失败时跳过，不阻塞整次请求。
     */
    public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() {
        java.util.List<dev.langchain4j.agent.tool.ToolSpecification> specs = new ArrayList<>();
        // ConcurrentHashMap 的弱一致性快照：动态注册/回收不会导致遍历异常，
        // 单次模型请求看到注册前或注册后的完整工具对象，下一轮自然刷新。
        for (RegisteredTool registered : new ArrayList<>(tools.values())) {
            try {
                specs.add(ToolSpecificationFactory.build(registered.tool()));
            } catch (Exception e) {
                // 跳过无法生成 spec 的工具
            }
        }
        return specs;
    }

    //内置工具直接实例化注册
    private boolean isToolEnabled(String toolName) {
        // 检查配置中是否启用了该工具
        if (appConfig == null || appConfig.getTools() == null) {
            return true; // 默认启用
        }

        switch (toolName) {
            case "bash":
                return appConfig.getTools().getBash().isEnabled();
            case "read":
                return appConfig.getTools().getRead().isEnabled();
            case "write":
                return appConfig.getTools().getWrite().isEnabled();
            case "edit":
                return appConfig.getTools().getEdit().isEnabled();
            case "glob":
                return appConfig.getTools().getGlob().isEnabled();
            default:
                return true;
        }
    }
}
