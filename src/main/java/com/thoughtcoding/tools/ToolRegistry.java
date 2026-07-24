package com.thoughtcoding.tools;

import com.thoughtcoding.config.AppConfig;

import java.util.*;

/**
 * 负责工具注册、发现和生命周期管理
 *
 * 工具系统的核心管理者，维护了所有可用工具的映射表，并提供统一的调用接口，
 */
public class ToolRegistry {
    private final Map<String, BaseTool> tools;
    private final AppConfig appConfig;

    public ToolRegistry(AppConfig appConfig) {
        this.tools = new HashMap<>();
        this.appConfig = appConfig;
    }

    // 🔥 统一注册入口，接受 BaseTool（内置工具与 MCP 工具共用）
    public void register(BaseTool tool) {
        if (isToolEnabled(tool.getName())) {
            tools.put(tool.getName(), tool);
        }
    }

    public BaseTool getTool(String toolName) {
        return tools.get(toolName);
    }

    /**
     * 🔥 把所有已启用工具（内置 + MCP）映射为 langchain4j 原生 ToolSpecification，
     * 在每次请求时调用以纳入运行时连接的 MCP 工具。单个工具转换失败时跳过，不阻塞整次请求。
     */
    public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() {
        java.util.List<dev.langchain4j.agent.tool.ToolSpecification> specs = new ArrayList<>();
        for (BaseTool tool : tools.values()) {
            try {
                specs.add(ToolSpecificationFactory.build(tool));
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
            case "file_manager":
                return appConfig.getTools().getFileManager().isEnabled();
            case "command_executor":
                return appConfig.getTools().getCommandExec().isEnabled();
            case "code_executor":
                return appConfig.getTools().getCodeExecutor().isEnabled();
            case "grep_search":
                return appConfig.getTools().getSearch().isEnabled();
            default:
                return true;
        }
    }
}
