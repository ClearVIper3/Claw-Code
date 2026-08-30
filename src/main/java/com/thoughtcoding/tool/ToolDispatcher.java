package com.thoughtcoding.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.core.CancelledException;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;

import java.util.Map;
import java.util.Objects;

/**
 * 工具执行的唯一收口。
 *
 * <p>原生 function calling 与 MCP 工具都通过 {@link #dispatch(ToolCall)} 执行。
 * Dispatcher 负责把查找、参数序列化及工具运行时异常统一收敛为非空 {@link ToolResult}，
 * 保证调用方总能为 provider tool call 写入配对结果。
 * 权限检查发生在 Dispatcher 之前，不由本类负责。
 */
public class ToolDispatcher {

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
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
    public ToolResult dispatch(ToolCall call, CancelToken token) {
        long start = System.currentTimeMillis();
        if (call == null) {
            return ToolResult.error("无效的工具调用：call 不能为空。", elapsedSince(start));
        }

        String toolName = call.getToolName();
        if (toolName == null || toolName.isBlank()) {
            return ToolResult.error("无效的工具调用：toolName 不能为空。", elapsedSince(start));
        }

        BaseTool tool = registry.getTool(toolName);
        if (tool == null) {
            return ToolResult.error("Tool not found: " + toolName, elapsedSince(start));
        }

        String argsJson;
        try {
            argsJson = toJson(call.getParameters());
        } catch (Exception e) {
            return ToolResult.error("工具参数序列化失败 [" + toolName + "]: " + exceptionMessage(e),
                    elapsedSince(start));
        }

        try {
            ToolResult result = tool.execute(argsJson, token);
            if (result == null) {
                return ToolResult.error("工具返回了空结果 [" + toolName + "]。", elapsedSince(start));
            }
            return result;
        } catch (CancelledException e) {
            return ToolResult.error("工具执行已取消 [" + toolName + "]: " + exceptionMessage(e),
                    elapsedSince(start));
        } catch (Exception e) {
            return ToolResult.error("工具执行异常 [" + toolName + "]: " + exceptionMessage(e),
                    elapsedSince(start));
        }
    }

    /** 把参数 Map 序列化为 JSON 字符串（各工具的 execute(String) 统一按 JSON 解析）。 */
    private String toJson(Map<String, Object> parameters) throws Exception {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        return mapper.writeValueAsString(parameters);
    }

    private long elapsedSince(long start) {
        return System.currentTimeMillis() - start;
    }

    private String exceptionMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
