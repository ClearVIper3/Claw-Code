package com.thoughtcoding.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;

import java.util.Map;

/**
 * 工具执行的唯一收口。
 *
 * 所有工具执行 —— 原生 function calling、MCP 工具、以及自动编译/运行 —— 都必须经过
 * {@link #dispatch(ToolCall)}。这样未来的 workspace 沙箱只需在此一处插桩，即可以结构化的
 * (name, args) 看到 100% 的写/执行操作。
 */
public class ToolDispatcher {

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行一个工具调用：查表 → 序列化参数 →（沙箱检查）→ 执行。
     */
    public ToolResult dispatch(ToolCall call) {
        long start = System.currentTimeMillis();

        BaseTool tool = registry.getTool(call.getToolName());
        if (tool == null) {
            return ToolResult.error("Tool not found: " + call.getToolName(), System.currentTimeMillis() - start);
        }

        String argsJson = toJson(call.getParameters());

        // 【沙箱插桩点】——下一任务在此插入 workspace 边界检查：
        //   WriteGuard.check(call.getToolName(), call.getParameters())
        // 对 file_manager 的 write/create/delete 路径、command_executor 的 command 做越界判定，
        // 越界时直接 return ToolResult.error(...) 不进入 tool.execute。

        return tool.execute(argsJson);
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
