package com.thoughtcoding.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.hook.HookType;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SubagentTurn;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolCallRef;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.PermissionHook;
import com.thoughtcoding.ui.ThoughtCodingUI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubAgent循环 —— 在【全新、隔离】的对话历史里跑一个独立的 agentic 循环。
 *
 * <p>与主 {@link AgentLoop} 的区别：
 * <ul>
 *   <li>自带独立 history（不碰主对话），只回传最终结论，中间过程丢弃；</li>
 *   <li>看不到 {@code subAgent} 工具（已从其可见的工具规格中过滤），因此不可能递归；</li>
 *   <li>内部工具调用照样走 PRE_TOOL_USE 权限管道（写/执行类仍需确认）；</li>
 *   <li>调用 {@link com.thoughtcoding.service.AIService#chatOnceForSubagent} —— 隔离的模型往返，
 *       不抢占主循环共享的流式回调/生成状态。</li>
 * </ul>
 *
 * <p>由 {@code SubAgentTool} 在被 dispatch 时同步创建并运行；此时主循环已阻塞在 dispatch 中，
 * 复用同一个底层模型是顺序、无并发的，安全。
 */
public class SubAgent {

    private final ThoughtCodingContext context;

    public SubAgent(ThoughtCodingContext context) {
        this.context = context;
    }

    /**
     * 运行SubAgent直到得出结论或达到最大轮次。
     *
     * @param subAgentPrompt 交给SubAgent的详细任务指令（作为它的首条 user 消息）
     * @param label      简短标签，仅用于终端展示
     * @param token      取消令牌（主回合 token 或后台任务私有 token），可为 null（不可取消）
     * @return SubAgent的最终结论文本（唯一回传给主Agent的内容）
     */
    public String run(String subAgentPrompt, String label, CancelToken token) {
        ThoughtCodingUI ui = context.getUi();
        ObjectMapper mapper = new ObjectMapper();

        printLine(ui, "[SubAgent] 开始: " + oneLine(label, 80));

        // 全新隔离历史：SubAgent看不到主对话，任务信息全在 subAgentPrompt 里
        List<ChatMessage> subHistory = new ArrayList<>();
        subHistory.add(new ChatMessage("user", subAgentPrompt));

        // SubAgent自己的权限栈（共享 UI；auto-approve 默认 false —— 更安全的方向）。
        // ownerLabel 让并发子代理的确认框能区分来源。
        ToolExecutionConfirmation confirmation =
                new ToolExecutionConfirmation(ui, ui.getLineReader(), "[SubAgent " + label + "]",
                        context::getConsoleInputRouter);
        HookRegistry hookRegistry = context.getHookRegistry().copy();
        hookRegistry.registerFirst(HookType.PRE_TOOL_USE, new PermissionHook(confirmation));
        ToolExecutionPipeline toolPipeline = new ToolExecutionPipeline(
                context, hookRegistry, context.getToolRegistry());

        String subPrompt = context.getContextManager().buildSubagentSystemPrompt();

        int maxIter = 30;

        String lastText = "";

        for (int iter = 0; iter < maxIter; iter++) {
            if (token != null && token.isCancelled()) {
                printLine(ui, "[SubAgent] 已被用户取消");
                return "子Agent已被用户取消，未产出结论。";
            }
            // 每轮首个 token 前打一个 [SubAgent] 前缀，其余 token 原样流式打印
            final boolean[] headerPrinted = {false};
            SubagentTurn turn = context.getAiService().chatOnceForSubagent(
                    subPrompt, subHistory,
                    t -> {
                        if (!headerPrinted[0]) {
                            printRaw(ui, "\n[SubAgent] ");
                            headerPrinted[0] = true;
                        }
                        printRaw(ui, t);
                    }, token);
            if (headerPrinted[0]) {
                printRaw(ui, "\n");
                flush(ui);
            }

            lastText = turn.getText();

            // 无工具调用 → 本轮即最终结论
            if (!turn.hasToolCalls()) {
                printLine(ui, "[SubAgent] 结束");
                return lastText;
            }

            // 记录携带工具调用的 assistant 消息（供下一轮重建 AiMessage.toolExecutionRequests）
            subHistory.add(ChatMessage.assistantWithToolCalls(turn.getText(), turn.getToolCalls()));

            // 逐个执行 —— 关键不变量：每个工具调用必须严格配对恰好一个 tool 结果，
            // 否则SubAgent绕过了主链路的 sanitizeToolPairs，下一轮会 400。
            for (ToolCallRef ref : turn.getToolCalls()) {
                String id = ref.getId();
                String name = ref.getName();

                Map<String, Object> params = parseArgs(mapper, ref.getArguments());
                ToolCall call = new ToolCall(name, params, null, false, 0, false, id);

                printLine(ui, "[SubAgent] 调用 " + name + argSummary(params));

                ToolExecutionPipeline.Outcome outcome =
                        toolPipeline.executeAndRecord(call, token, subHistory);
                ToolResult result = outcome.result();
                if (outcome.isBlocked()) {
                    printLine(ui, "[SubAgent] 结果: 已阻止 —— "
                            + oneLine(result.getError(), 80));
                } else {
                    printLine(ui, "[SubAgent] 结果: " + (result.isSuccess()
                            ? "成功" : ("失败 —— " + oneLine(result.getError(), 80))));
                }
            }
        }

        // 达到最大轮次：给出兜底结论，避免返回空串
        printLine(ui, "[SubAgent] 已达最大工具轮次(" + maxIter + ")，停止。");
        return (lastText == null || lastText.isBlank())
                ? "SubAgent已达最大工具轮次(" + maxIter + ")，未产出最终结论。"
                : lastText;
    }

    // ── 显示辅助（纯文本、无装饰 emoji；直接走 terminal writer 以完全控制格式）──

    private void printLine(ThoughtCodingUI ui, String text) {
        if (ui == null || ui.getTerminal() == null) return;
        ui.getTerminal().writer().println(text);
        ui.getTerminal().writer().flush();
    }

    private void printRaw(ThoughtCodingUI ui, String text) {
        if (ui == null || ui.getTerminal() == null) return;
        ui.getTerminal().writer().print(text);
    }

    private void flush(ThoughtCodingUI ui) {
        if (ui == null || ui.getTerminal() == null) return;
        ui.getTerminal().writer().flush();
    }

    /** 解析工具参数 JSON 为 Map；失败则退化为 {"input": 原始串}（与主服务一致的兜底）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(ObjectMapper mapper, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return mapper.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("input", argumentsJson);
            return fallback;
        }
    }

    /** 从参数里挑一个有代表性的值做单行摘要（command/path/pattern 等），仅用于展示。 */
    private String argSummary(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        for (String key : new String[]{"command", "path", "file_path", "pattern"}) {
            Object v = params.get(key);
            if (v != null) return " (" + oneLine(v.toString(), 60) + ")";
        }
        return "";
    }

    private String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
