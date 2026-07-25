package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolExecution;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.tools.ToolDispatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 交互的核心循环。
 *
 * 基于 langchain4j 原生 function calling 的多轮 agentic 循环：
 * 用户输入 → 模型响应（可能请求工具）→ 执行工具（仅写/执行类确认）→ 结果按 id 配对回喂 →
 * 无新输入再问模型，直到模型不再请求工具、用户取消、或达到 maxToolIterations。
 */
public class AgentLoop {
    private final ThoughtCodingContext context;
    private final List<ChatMessage> history;
    private final String sessionId;
    private final String modelName;
    private final ToolExecutionConfirmation confirmation;  // 交互式确认组件
    private final ToolDispatcher toolDispatcher;           // 工具执行唯一收口（沙箱插桩点）

    // 缓存本轮模型请求的工具调用（原生路径一轮可能有多个）
    private final List<ToolCall> pendingToolCalls = new ArrayList<>();

    public AgentLoop(ThoughtCodingContext context, String sessionId, String modelName) {
        this.context = context;
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = new ArrayList<>();

        this.confirmation = new ToolExecutionConfirmation(
            context.getUi(),
            context.getUi().getLineReader()
        );
        this.toolDispatcher = new ToolDispatcher(context.getToolRegistry());

        // 设置消息和工具调用处理器
        context.getAiService().setMessageHandler(this::handleMessage);
        context.getAiService().setToolCallHandler(this::handleToolCall);
    }

    public void loadHistory(List<ChatMessage> previousHistory) {
        if (previousHistory != null) {
            history.addAll(previousHistory);
        }
    }

    public void processInput(String input) {
        PerformanceMonitor monitor = context.getPerformanceMonitor();
        monitor.start();

        try {
            pendingToolCalls.clear();
            history.add(new ChatMessage("user", input));

            // 原生 function calling：多轮 agentic 循环
            runNativeToolLoop();

            context.getSessionService().saveSession(sessionId, history);
        } catch (Exception e) {
            context.getUi().displayError("Error processing input: " + e.getMessage());
        } finally {
            monitor.stop();
        }
    }

    private void handleMessage(ChatMessage message) {
        // 流式输出的实时显示
        context.getUi().displayAIMessage(message);
        // 不在这里写历史：LangChainService 在流式完成后写入完整的 assistant 消息
    }

    private void handleToolCall(ToolCall toolCall) {
        // 缓存工具调用（原生路径一轮可累积多个），等模型响应完成后统一执行
        this.pendingToolCalls.add(toolCall);
    }

    /**
     * 原生 function calling 的多轮 agentic 循环。
     */
    private void runNativeToolLoop() {
        AppConfig.AIConfig ai = context.getAppConfig().getAi();
        int maxIter = ai != null ? ai.getMaxToolIterations() : 10;
        boolean auto = ai == null || ai.isAutoProcessToolResults();
        int iter = 0;

        while (true) {
            pendingToolCalls.clear();
            // 一轮模型响应（无新用户输入；用户消息与历史已在 history 中）
            context.getAiService().streamingChat(null, history, modelName);

            if (pendingToolCalls.isEmpty()) {
                break; // 模型只产出文本 → 自然终止
            }

            List<ToolCall> batch = new ArrayList<>(pendingToolCalls);
            boolean stop = false;

            for (ToolCall call : batch) {
                if (stop) {
                    // 用户已取消：为剩余工具补 declined 结果，保持 call/result 配对
                    history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(),
                            "用户已取消后续工具执行。"));
                    continue;
                }

                // 仅写/执行类需要确认（除非处于自动批准模式）
                if (requiresConfirmation(call) && !confirmation.isAutoApproveMode()) {
                    ToolExecution exec = new ToolExecution(
                            call.getToolName(),
                            call.getDescription() != null ? call.getDescription() : "执行工具操作",
                            call.getParameters(),
                            true);
                    ToolExecutionConfirmation.ActionType action = confirmation.askConfirmationWithOptions(exec);
                    if (action == ToolExecutionConfirmation.ActionType.NO) {
                        context.getUi().displayWarning("⏭️  已取消：" + describeTool(call));
                        history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(),
                                "用户拒绝执行该工具。"));
                        stop = true;
                        continue;
                    }
                }

                // 执行并把结果按 id 配对写回 history
                ToolResult result = toolDispatcher.dispatch(call);
                displayNativeToolResult(call, result);
                String resultText = result.isSuccess()
                        ? (result.getOutput() == null || result.getOutput().isBlank()
                            ? "执行成功（无输出）。" : result.getOutput())
                        : ("执行失败: " + result.getError());
                history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(), resultText));
            }

            if (stop) {
                break;      // 用户 DISCARD → 停止循环，交回用户
            }
            if (!auto) {
                break;      // 不自动回喂结果 → 执行一批后停止
            }
            if (++iter >= maxIter) {
                history.add(new ChatMessage("system",
                        "已达到最大工具调用轮次(" + maxIter + ")，停止自动执行。"));
                context.getUi().displayWarning("⚠️  已达最大工具轮次(" + maxIter + ")，停止自动执行。");
                break;
            }
        }
    }

    /** 写/执行类工具需要确认；只读工具（由工具自身 isReadOnly() 声明）静默放行。 */
    private boolean requiresConfirmation(ToolCall call) {
        String name = call.getToolName();
        if (name == null) {
            return true;
        }
        BaseTool tool = context.getToolRegistry().getTool(name);
        // 未知/MCP 工具（未声明只读）默认需确认；工具自身声明 isReadOnly 则静默放行。
        return tool == null || !tool.isReadOnly();
    }

    private String describeTool(ToolCall call) {
        String cmd = extractCommand(call);
        if (cmd != null) {
            return call.getToolName() + "(" + cmd + ")";
        }
        String fn = extractFileName(call);
        if (fn != null) {
            return call.getToolName() + "(" + fn + ")";
        }
        return call.getToolName();
    }

    /** 显示原生工具执行结果。 */
    private void displayNativeToolResult(ToolCall call, ToolResult result) {
        if (result.isSuccess()) {
            context.getUi().displaySuccess("✅ 完成: " + describeTool(call));
            String output = result.getOutput();
            if (output != null && !output.trim().isEmpty()) {
                for (String line : output.trim().split("\n")) {
                    context.getUi().getTerminal().writer().println("  " + line);
                }
                context.getUi().getTerminal().writer().flush();
            }
        } else {
            context.getUi().displayError("❌ 失败: " + result.getError());
        }
    }

    /** 从工具调用参数中提取命令（command/input），用于展示。 */
    private String extractCommand(ToolCall toolCall) {
        if (toolCall.getParameters() == null) {
            return null;
        }
        Object command = toolCall.getParameters().get("command");
        if (command != null) {
            return command.toString();
        }
        Object input = toolCall.getParameters().get("input");
        if (input != null) {
            return input.toString();
        }
        return null;
    }

    /** 从工具调用参数中提取文件名（path 的末段），用于展示。 */
    private String extractFileName(ToolCall toolCall) {
        if (toolCall.getParameters() == null) {
            return null;
        }
        Object path = toolCall.getParameters().get("path");
        if (path != null) {
            String pathStr = path.toString();
            int lastSlash = Math.max(pathStr.lastIndexOf('/'), pathStr.lastIndexOf('\\'));
            if (lastSlash >= 0 && lastSlash < pathStr.length() - 1) {
                return pathStr.substring(lastSlash + 1);
            }
            return pathStr;
        }
        return null;
    }

    /** 设置自动批准模式（用于批量操作）。 */
    public void setAutoApprove(boolean enabled) {
        confirmation.setAutoApproveMode(enabled);
    }

    /** 是否处于自动批准模式。 */
    public boolean isAutoApproveMode() {
        return confirmation.isAutoApproveMode();
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public String getSessionId() {
        return sessionId;
    }
}
