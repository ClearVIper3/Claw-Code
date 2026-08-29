package com.thoughtcoding.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;

import java.util.Map;

/**
 * 工具执行的唯一收口。
 *
 * 所有工具执行 —— 原生 function calling、MCP 工具、以及自动编译/运行 —— 都必须经过
 * {@link #dispatch(ToolCall)}。权限检查由 AgentLoop 调用 {@link PermissionGate} 完成，
 * 工具内部通过 {@link Sandbox#resolve} 只做路径解析，不做权限决策。
 */
public class ToolDispatcher {

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行一个工具调用：查表 → 序列化参数 → 执行（无取消令牌，等价于不可取消）。
     */
    public ToolResult dispatch(ToolCall call) {
        return dispatch(call, null);
    }

    /**
     * 执行一个工具调用：查表 → 序列化参数 → 携带取消令牌执行。
     * token 为 null 表示本调用不可取消。
     */
    public ToolResult dispatch(ToolCall call, com.thoughtcoding.core.CancelToken token) {
        long start = System.currentTimeMillis();

        BaseTool tool = registry.getTool(call.getToolName());
        if (tool == null) {
            return ToolResult.error("Tool not found: " + call.getToolName(), System.currentTimeMillis() - start);
        }

        String argsJson = toJson(call.getParameters());

        return tool.execute(argsJson, token);
    }

    /** 把参数 Map 序列化为 JSON 字符串（各工具的 execute(String) 统一按 JSON 解析）。 */
    private String toJson(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(parameters);
        } catch (Exception e) {
            StringBuilder json = new StringBuilder("{");
            parameters.forEach((k, v) -> json.append("\"").append(k).append("\":\"").append(v).append("\","));
            if (json.length() > 1) {
                json.setLength(json.length() - 1);
            }
            return json.append("}").toString();
        }
    }
}
