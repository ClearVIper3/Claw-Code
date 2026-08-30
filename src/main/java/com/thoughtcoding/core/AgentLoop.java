package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.hook.HookContext;
import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.hook.HookResult;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.security.PermissionHook;
import com.thoughtcoding.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final Set<String> QUIET_OUTPUT_TOOLS = Set.of("read", "glob", "skill");
    /** 防止 STOP Hook 持续要求续跑导致无界循环。 */
    static final int MAX_STOP_CONTINUATIONS = 3;

    private final ThoughtCodingContext context;
    private final List<ChatMessage> history;
    private final String sessionId;
    private final String modelName;
    private final ToolExecutionConfirmation confirmation;  // 交互式确认组件
    private final ToolDispatcher toolDispatcher;
    private final HookRegistry hookRegistry;               // 基于注册表的 Hook 系统

    // 缓存本轮模型请求的工具调用（原生路径一轮可能有多个）
    private final List<ToolCall> pendingToolCalls = new ArrayList<>();

    public AgentLoop(ThoughtCodingContext context, String sessionId, String modelName) {
        this.context = context;
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = new ArrayList<>();

        this.confirmation = new ToolExecutionConfirmation(
            context.getUi(),
            context.getUi().getLineReader(),
            null,
            context::getConsoleInputRouter    // Runner 晚于 Loop 创建，执行确认时再动态获取
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
        processInput(input, new CancelToken());
    }

    /**
     * 处理一次用户输入（一个 agent 回合）。
     *
     * @param input 用户输入
     * @param token 本回合的取消令牌（由 AgentTurnRunner 创建；单次提问模式传入独立令牌）
     */
    public void processInput(String input, CancelToken token) {
        PerformanceMonitor monitor = context.getPerformanceMonitor();
        monitor.start();

        try {
            pendingToolCalls.clear();

            // ── Hook: UserPromptSubmit（进入 LLM 前）—— BLOCK 则跳过本轮 ──
            HookContext promptContext = HookContext.forUserPrompt(context, history, input);
            HookResult promptResult = hookRegistry.fire(promptContext);
            if (promptResult.isBlocked()) {
                context.getUi().displayWarning(promptResult.message() != null
                        ? promptResult.message() : "本轮输入已被 Hook 阻止。");
                return;
            }

            // 后台子代理结论注入：在用户新输入之前插入，让模型先看到已完成任务的结论
            injectCompletedBackgroundTasks();

            history.add(new ChatMessage("user", promptContext.buildPromptForModel()));

            // 原生 function calling：多轮 agentic 循环
            runNativeToolLoop(token);

            context.getSessionService().saveSession(sessionId, history);
        } catch (Exception e) {
            context.getUi().displayError("Error processing input: " + e.getMessage());
        } finally {
            monitor.stop();
        }
    }

    /** 把已完成的后台子代理结论作为 user 消息注入主历史（一次性 drain）。 */
    private void injectCompletedBackgroundTasks() {
        for (SubAgentExecutor.BackgroundTask t : context.getSubAgentExecutor().drainCompleted()) {
            String conclusion = t.conclusion() == null || t.conclusion().isBlank()
                    ? "（无结论）" : t.conclusion();
            history.add(new ChatMessage("user",
                    "[后台子Agent " + t.label() + "（" + t.id() + "）已完成，结论如下]\n" + conclusion));
            context.getUi().displayInfo("📎 已注入后台子Agent结论: " + t.label());
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
     *
     * <p>取消语义：token 触发后，本轮流式立即停止、已缓存但未执行的工具调用
     * 补「用户已取消」配对结果，循环退出——保证 history 中的工具调用/结果
     * 严格配对（违反会导致模型 400）。
     */
    private void runNativeToolLoop(CancelToken token) {
        AppConfig.AIConfig ai = context.getAppConfig().getAi();
        int maxIter = ai != null ? ai.getMaxToolIterations() : 10;
        boolean auto = ai == null || ai.isAutoProcessToolResults();
        int iter = 0;
        int stopContinuations = 0;

        while (true) {
            pendingToolCalls.clear();
            // 一轮模型响应（无新用户输入；用户消息与历史已在 history 中）
            context.getAiService().streamingChat(null, history, modelName, token);
            // 空一行，避免与后续工具确认/结果挤在一起
            context.getUi().getTerminal().writer().println();
            context.getUi().getTerminal().flush();

            // 流式被中断：丢弃残余，为已缓存的调用补配对后退出
            if (token.isCancelled()) {
                fillCancelledResults(pendingToolCalls, 0, history);
                context.getUi().displayWarning("⏸️  本轮任务已被用户中断。");
                break;
            }

            if (pendingToolCalls.isEmpty()) {
                // ── Hook: Stop（循环即将退出）──
                HookResult stopResult = hookRegistry.fire(HookContext.forStop(context, history));
                if (stopResult.isContinueLoop()) {
                    if (stopContinuations >= MAX_STOP_CONTINUATIONS) {
                        context.getUi().displayWarning("⚠️  STOP Hook 本轮续跑已达上限("
                                + MAX_STOP_CONTINUATIONS + ")，强制结束本轮。");
                        break;
                    }
                    stopContinuations++;
                    history.add(new ChatMessage("user", buildStopContinuationPrompt(stopResult)));
                    continue;
                }
                break; // 模型只产出文本 → 自然终止
            }

            List<ToolCall> batch = new ArrayList<>(pendingToolCalls);

            // ── 批内 subAgent 并行：≥2 个 subAgent 调用时预执行（虚拟线程），
            //    其余工具保持串行。结果按调用实例暂存，下面按原顺序统一回喂。
            Map<ToolCall, ToolResult> parallelResults = preExecuteSubAgents(batch, token);

            boolean cancelled = false;

            for (int i = 0; i < batch.size(); i++) {
                ToolCall call = batch.get(i);

                if (token.isCancelled()) {
                    // 用户已取消：为剩余工具一次性补 declined 结果，保持 call/result 配对
                    fillCancelledResults(batch, i, history);
                    cancelled = true;
                    break;
                }

                // ── Hook: PreToolUse（工具执行前）—— 权限检查 + 自定义动作 ──
                // PermissionHook 已注册在此时机：DENY → BLOCK，WARN → 弹确认
                // （并行路径的 subAgent 已在 preExecuteSubAgents 内触发过 hook）
                ToolResult result;
                if (parallelResults.containsKey(call)) {
                    result = parallelResults.get(call);
                } else {
                    HookResult preResult = hookRegistry.fire(
                            HookContext.forPreTool(context, history, call));
                    if (preResult.isBlocked()) {
                        String msg = preResult.message() != null ? preResult.message() : "工具执行被阻止。";
                        context.getUi().displayError(msg);
                        history.add(ChatMessage.toolResult(
                            call.getProviderCallId(), call.getToolName(), msg));
                        continue;
                    }

                    // 执行并把结果按 id 配对写回 history
                    result = toolDispatcher.dispatch(call, token);
                }

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

            if (cancelled) {
                break;      // 用户中断 → 停止循环，交回用户
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

    /**
     * 批内 subAgent 并行执行（≥2 个才值得并行；单个走原串行路径）。
     *
     * <p>每个任务在 SubAgentExecutor 的虚拟线程上跑（Semaphore 限流），
     * PreToolUse hook 在任务内触发（subAgent 恒为 ALLOW，无确认弹窗冲突）。
     * 任何异常都收敛为失败的 ToolResult，绝不逃逸。
     *
     * @return 调用实例 → 结果；串行路径（0/1 个 subAgent）返回空 Map
     */
    private Map<ToolCall, ToolResult> preExecuteSubAgents(List<ToolCall> batch, CancelToken token) {
        List<ToolCall> subCalls = new ArrayList<>();
        for (ToolCall c : batch) {
            if ("subAgent".equals(c.getToolName())) {
                subCalls.add(c);
            }
        }
        Map<ToolCall, ToolResult> results = new java.util.IdentityHashMap<>();
        if (subCalls.size() < 2) {
            return results;
        }

        context.getUi().displayInfo("🚀 " + subCalls.size() + " 个子Agent并行执行中...");

        List<java.util.concurrent.Future<ToolResult>> futures = new ArrayList<>();
        for (ToolCall c : subCalls) {
            futures.add(context.getSubAgentExecutor().submitForeground(() -> {
                HookResult pre = hookRegistry.fire(HookContext.forPreTool(context, history, c));
                if (pre.isBlocked()) {
                    return ToolResult.error(pre.message() != null ? pre.message() : "工具执行被阻止。", 0);
                }
                return toolDispatcher.dispatch(c, token);
            }));
        }

        for (int i = 0; i < subCalls.size(); i++) {
            ToolCall c = subCalls.get(i);
            try {
                results.put(c, futures.get(i).get());
            } catch (Exception e) {
                results.put(c, ToolResult.error("子Agent执行异常: " + e.getMessage(), 0));
            }
        }
        return results;
    }

    /**
     * 为批内 [fromIndex, end) 的调用补「用户已取消」配对结果。
     * 静态且无副作用依赖，便于单测直接验证配对不变量。
     */
    static void fillCancelledResults(List<ToolCall> batch, int fromIndex, List<ChatMessage> history) {
        for (int i = fromIndex; i < batch.size(); i++) {
            ToolCall call = batch.get(i);
            history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(),
                    "用户已取消后续工具执行。"));
        }
    }

    /** 将 STOP Hook 的续跑原因转为下一轮模型可见的明确指令。 */
    static String buildStopContinuationPrompt(HookResult result) {
        String reason = result != null ? result.message() : null;
        if (reason == null || reason.isBlank()) {
            reason = "当前任务尚未完成，请继续执行。";
        }
        return "[STOP Hook 请求继续执行]\n" + reason;
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
