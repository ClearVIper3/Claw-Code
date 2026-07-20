package com.thoughtcoding.model;

import java.util.Map;

/**
 * 记录工具调用的详细信息
 *
 * 包含工具名称、参数、结果、执行时间等
 */
public class ToolCall {
    private final String id;
    private final String toolName;
    private final String description;
    private final Map<String, Object> parameters;
    private final String result;
    private final boolean success;
    private final long executionTime;
    private final boolean streamingTriggered;  // 🔥 新增：标记是否是流式触发的工具调用
    private final String providerCallId;        // 🔥 原生工具调用 id（= ToolExecutionRequest.id），用于把结果配对回传；旧路径为 null

    public ToolCall(String toolName, Map<String, Object> parameters, String result, boolean success, long executionTime) {
        this(toolName, parameters, result, success, executionTime, false);
    }

    // 🔥 新增：支持 streamingTriggered 参数的构造函数
    public ToolCall(String toolName, Map<String, Object> parameters, String result, boolean success, long executionTime, boolean streamingTriggered) {
        this(toolName, parameters, result, success, executionTime, streamingTriggered, null);
    }

    // 🔥 新增：支持 providerCallId 的构造函数（原生 function calling 路径使用）
    public ToolCall(String toolName, Map<String, Object> parameters, String result, boolean success, long executionTime, boolean streamingTriggered, String providerCallId) {
        this.id = java.util.UUID.randomUUID().toString();
        this.toolName = toolName;
        this.description = "执行 " + toolName + " 工具";
        this.parameters = parameters;
        this.result = result;
        this.success = success;
        this.executionTime = executionTime;
        this.streamingTriggered = streamingTriggered;
        this.providerCallId = providerCallId;
    }

    // Getters
    public String getId() { return id; }
    public String getToolName() { return toolName; }
    public String getDescription() { return description; }
    public Map<String, Object> getParameters() { return parameters; }
    public String getResult() { return result; }
    public boolean isSuccess() { return success; }
    public long getExecutionTime() { return executionTime; }
    public boolean isStreamingTriggered() { return streamingTriggered; }  // 🔥 新增 getter
    public String getProviderCallId() { return providerCallId; }          // 🔥 新增 getter

    @Override
    public String toString() {
        return String.format("ToolCall{name=%s, success=%s, time=%dms}", toolName, success, executionTime);
    }
}