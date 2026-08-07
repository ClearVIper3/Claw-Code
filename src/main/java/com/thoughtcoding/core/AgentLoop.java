package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.background.BackgroundTaskManager;
import com.thoughtcoding.hook.HookContext;
import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.hook.HookResult;
import com.thoughtcoding.memory.MemoryService;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.security.PermissionHook;
import com.thoughtcoding.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AI 交互的核心循环。
 *
 * 基于 langchain4j 原生 function calling 的多轮 agentic 循环：
 * 用户输入 → 模型响应（可能请求工具）→ 权限检查 → 执行工具（写/执行类 + 越界只读类确认）→ 结果按 id 配对回喂 →
 * 无新输入再问模型，直到模型不再请求工具、用户取消、或达到 maxToolIterations。
 */
public class AgentLoop {
    /** 只读工具：结果回喂模型即可，不在用户端 dump 内容（避免刷屏）。 */
    private static final Set<String> QUIET_OUTPUT_TOOLS = Set.of("read", "glob", "skill", "task_get");

    /** 后台工具（bash）的占位 tool_result 文案：配对保持 + 提示模型结果稍后以通知到达。 */
    private static final String BG_PLACEHOLDER =
            "任务已在后台启动，完成后会通过 <task_notification> 返回结果。";

    private final ThoughtCodingContext context;
    private final List<ChatMessage> history;
    private final String sessionId;
    private final String modelName;
    private final ToolExecutionConfirmation confirmation;  // 交互式确认组件
    private final ToolDispatcher toolDispatcher;
    private final HookRegistry hookRegistry;               // 基于注册表的 Hook 系统

    // 缓存本轮模型请求的工具调用（原生路径一轮可能有多个）
    private final List<ToolCall> pendingToolCalls = new ArrayList<>();

    // 🔥 后台任务管理器：耗时工具卸载到守护线程池，结果以 <task_notification> 注入
    private final BackgroundTaskManager backgroundTasks = new BackgroundTaskManager();

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

        // 一开始先注册四种 hook 时机（动作由业务方按需 register 追加）
        this.hookRegistry = new HookRegistry();

        // 将权限检查注册为 PRE_TOOL_USE 的 hook 动作
        this.hookRegistry.register(com.thoughtcoding.hook.HookType.PRE_TOOL_USE,
                new PermissionHook(this.confirmation));

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

            // 🔥 上一回合结束后才完成的后台任务，在本轮用户输入之前注入通知——
            // 放在新用户消息之前，确保当前 query 仍是 prepareMessages 的尾部锚点。
            drainCompletedBackgroundTasks();

            // ── Hook: UserPromptSubmit（进入 LLM 前）—— BLOCK 则跳过本轮 ──
            HookResult promptResult = hookRegistry.fire(
                    HookContext.forUserPrompt(context, history, input));

            history.add(new ChatMessage("user", input));

            // ── 记忆：本轮开始前 LLM 召回相关记忆，注入 system prompt ──
            MemoryService memory = context.getMemoryService();
            if (memory != null) {
                context.getContextManager().setActiveMemories(memory.recall(history));
            }

            // 原生 function calling：多轮 agentic 循环
            runNativeToolLoop();

            // ── 记忆：本轮结束后同步储存新记忆 + 触发条件时整理(dream) ──
            if (memory != null) {
                memory.remember(history);
                memory.dream();
            }

            context.getSessionService().saveSession(sessionId, history);
        } catch (Exception e) {
            context.getUi().displayError("Error processing input: " + e.getMessage());
        } finally {
            context.getContextManager().clearActiveMemories();
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
            // 空一行，避免与后续工具确认/结果挤在一起
            context.getUi().getTerminal().writer().println();
            context.getUi().getTerminal().flush();

            if (pendingToolCalls.isEmpty()) {
                // ── Hook: Stop（循环即将退出）──
                hookRegistry.fire(HookContext.forStop(context, history));
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

                // ── Hook: PreToolUse（工具执行前）—— 权限检查 + 自定义动作 ──
                // PermissionHook 已注册在此时机：DENY → BLOCK，WARN → 弹确认
                HookResult preResult = hookRegistry.fire(
                        HookContext.forPreTool(context, history, call));
                if (preResult.isBlocked()) {
                    String msg = preResult.message() != null ? preResult.message() : "工具执行被阻止。";
                    context.getUi().displayError(msg);
                    history.add(ChatMessage.toolResult(
                        call.getProviderCallId(), call.getToolName(), msg));
                    continue;
                }

                // 🔥 后台任务：模型显式请求 run_in_background=true 时卸载到守护线程。
                // 权限/确认已在前台完成；占位 tool_result 立即补齐保持 call/result 配对，
                // 真实结果稍后以 <task_notification> 注入（见 drainCompletedBackgroundTasks）。
                if (shouldRunBackground(call)) {
                    String label = extractCommand(call);
                    String bgId = backgroundTasks.dispatch(call, toolDispatcher, label);
                    context.getUi().displayInfo("[background] 已派发 " + bgId
                            + (label != null ? " : " + label : ""));
                    history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(),
                            BG_PLACEHOLDER + " (task_id=" + bgId + ")"));
                    continue;
                }

                // 执行并把结果按 id 配对写回 history
                ToolResult result = toolDispatcher.dispatch(call);

                // ── Hook: PostToolUse（工具执行后）──
                hookRegistry.fire(HookContext.forPostTool(context, history, call, result));

                displayNativeToolResult(call, result);
                // 工具结果显示后空一行，避免与下一轮 AI 流式文本挤在同一区域
                context.getUi().getTerminal().writer().println();
                String resultText = result.isSuccess()
                        ? (result.getOutput() == null || result.getOutput().isBlank()
                            ? "执行成功（无输出）。" : result.getOutput())
                        : ("执行失败: " + result.getError());
                history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(), resultText));
            }

            // 🔥 收拢本批执行期间完成的后台任务：注入 <task_notification> 供下一轮 LLM 感知。
            // 放在 auto/maxIter 检查之前：auto 时 while(true) 会立刻再发一轮把通知喂给模型。
            drainCompletedBackgroundTasks();

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

        // 🔥 循环自然终止但仍有后台任务在跑：提示结果将在下一轮出现
        if (backgroundTasks.hasRunning()) {
            context.getUi().displayInfo("ℹ️  " + backgroundTasks.runningCount()
                    + " 个后台任务仍在运行，结果将在下一轮出现。");
        }
    }

    /** 是否应把该工具调用卸载到后台执行：仅 bash 且显式 run_in_background=true。 */
    private boolean shouldRunBackground(ToolCall call) {
        if (!"bash".equals(call.getToolName()) || call.getParameters() == null) {
            return false;
        }
        Object v = call.getParameters().get("run_in_background");
        return v instanceof Boolean ? (Boolean) v
                : v != null && "true".equalsIgnoreCase(v.toString());
    }

    /**
     * 收拢已完成的后台任务：触发 PostToolUse hook、打印完成提示，
     * 并以 role=user 的 <task_notification> 消息注入 history（持久、供下一轮 LLM 感知）。
     * 幂等：drainCompleted 取走即从注册表移除，同任务不会重复上报。
     */
    private void drainCompletedBackgroundTasks() {
        for (BackgroundTaskManager.BgTask t : backgroundTasks.drainCompleted()) {
            // PostToolUse 在结果就绪的主线程触发（派发时结果未就绪）
            hookRegistry.fire(HookContext.forPostTool(context, history, t.call, t.result));
            boolean ok = t.result != null && t.result.isSuccess();
            context.getUi().displayInfo("[background done] " + t.id + (ok ? " ✅" : " ❌"));
            history.add(new ChatMessage("user", buildTaskNotification(t)));
        }
    }

    /** 仿 s13：把后台任务结果拼成 <task_notification> 消息体。 */
    private String buildTaskNotification(BackgroundTaskManager.BgTask t) {
        String status = (t.result != null && t.result.isSuccess()) ? "completed" : "failed";
        String body = (t.result != null && t.result.isSuccess())
                ? t.result.getOutput() : (t.result != null ? t.result.getError() : null);
        if (body == null) {
            body = "";
        }
        if (body.length() > 4000) {
            body = body.substring(0, 4000) + "\n...(truncated)";
        }
        return "<task_notification>\n<task_id>" + t.id + "</task_id>\n<status>" + status
                + "</status>\n<command>" + (t.label == null ? "" : t.label) + "</command>\n<summary>"
                + body + "</summary>\n</task_notification>";
    }

    /** 关闭后台任务管理器（CLI 退出时调用；守护线程即便漏网也不阻塞 JVM 退出）。 */
    public void shutdownBackground() {
        backgroundTasks.shutdown();
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
        String skill = extractSkillName(call);
        if (skill != null) {
            return call.getToolName() + "(" + skill + ")";
        }
        return call.getToolName();
    }

    /** 从 skill 工具的调用参数中提取技能名（name），用于展示实际加载的技能。 */
    private String extractSkillName(ToolCall toolCall) {
        if (!"skill".equals(toolCall.getToolName()) || toolCall.getParameters() == null) {
            return null;
        }
        Object name = toolCall.getParameters().get("name");
        return name == null ? null : name.toString();
    }

    /** 显示原生工具执行结果。 */
    private void displayNativeToolResult(ToolCall call, ToolResult result) {
        // 🔥 subAgent（子Agent）的过程与结论已由子Agent实时打印，这里不再重复 dump 输出，避免刷屏。
        //    结论仍照常写回 history 回喂模型（见调用处），不受影响。
        if ("subAgent".equals(call.getToolName())) {
            if (result.isSuccess()) {
                context.getUi().getTerminal().writer().println("└ 子Agent已返回结论");
                context.getUi().getTerminal().writer().flush();
            } else {
                context.getUi().displayError("❌ 子Agent失败: " + result.getError());
            }
            return;
        }
        if (result.isSuccess()) {
            context.getUi().displaySuccess("✅ 完成: " + describeTool(call));
            // 只读工具（read/glob/skill）的返回只需回喂模型，不在用户端 dump——
            // 否则 skill 正文、整份文件内容会刷屏。仿 Claude Code 的做法。
            if (QUIET_OUTPUT_TOOLS.contains(call.getToolName())) {
                return;
            }
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
