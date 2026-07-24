package com.thoughtcoding.tools;

import com.thoughtcoding.model.ToolResult;

/**
 * 工具的抽象基类，定义了工具的基本属性和行为
 */
public abstract class BaseTool {
    protected final String name;
    protected final String description;

    public BaseTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public abstract ToolResult execute(String input);

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 🔥 获取输入参数的Schema定义（用于MCP工具）
     * 子类可以重写此方法以提供参数schema信息给AI
     */
    public Object getInputSchema() {
        return null; // 默认返回null，MCP工具可以重写
    }

    protected ToolResult success(String output) {
        return ToolResult.success(output, 0);
    }

    protected ToolResult error(String error) {
        return ToolResult.error(error, 0);
    }

    protected ToolResult success(String output, long executionTime) {
        return ToolResult.success(output, executionTime);
    }

    protected ToolResult error(String error, long executionTime) {
        return ToolResult.error(error, executionTime);
    }

    /** 展开路径中的 ~ 为用户主目录（~ 或 ~/xxx）。 */
    protected String expandUserHome(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    @Override
    public String toString() {
        return String.format("Tool{name=%s, description=%s}", name, description);
    }
}