package com.thoughtcoding.hook;

import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.ui.ThoughtCodingUI;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 执行时传递给各动作的上下文载体。
 *
 * <p>不同时机携带不同字段（多数字段可空），动作按需读取/改写：
 * <ul>
 *   <li>{@link HookType#USER_PROMPT_SUBMIT}：读写 {@link #prompt}，可通过 {@link #injectContext} 注入附加上下文</li>
 *   <li>{@link HookType#PRE_TOOL_USE}：读 {@link #toolCall}</li>
 *   <li>{@link HookType#POST_TOOL_USE}：读 {@link #toolCall} 与 {@link #toolResult}</li>
 *   <li>{@link HookType#STOP}：读 {@link #history}</li>
 * </ul>
 *
 * <p>通过 {@link #getContext()} 访问应用上下文（UI/配置/会话/工具注册表等），
 * {@link #getUi()} 为其中最常用的输出能力提供快捷方式。
 */
public class HookContext {

    private final HookType type;
    private final ThoughtCodingContext context;
    private final List<ChatMessage> history;

    // USER_PROMPT_SUBMIT：可被动作改写的用户输入
    private String prompt;
    // USER_PROMPT_SUBMIT：动作追加的附加上下文（进入 LLM 前拼接）
    private final List<String> injectedContext = new ArrayList<>();

    // PRE/POST_TOOL_USE：当前工具调用
    private final ToolCall toolCall;
    // POST_TOOL_USE：工具执行结果
    private final ToolResult toolResult;

    private HookContext(HookType type, ThoughtCodingContext context, List<ChatMessage> history,
                        String prompt, ToolCall toolCall, ToolResult toolResult) {
        this.type = type;
        this.context = context;
        this.history = history;
        this.prompt = prompt;
        this.toolCall = toolCall;
        this.toolResult = toolResult;
    }

    public static HookContext forUserPrompt(ThoughtCodingContext context, List<ChatMessage> history, String prompt) {
        return new HookContext(HookType.USER_PROMPT_SUBMIT, context, history, prompt, null, null);
    }

    public static HookContext forPreTool(ThoughtCodingContext context, List<ChatMessage> history, ToolCall call) {
        return new HookContext(HookType.PRE_TOOL_USE, context, history, null, call, null);
    }

    public static HookContext forPostTool(ThoughtCodingContext context, List<ChatMessage> history,
                                          ToolCall call, ToolResult result) {
        return new HookContext(HookType.POST_TOOL_USE, context, history, null, call, result);
    }

    public static HookContext forStop(ThoughtCodingContext context, List<ChatMessage> history) {
        return new HookContext(HookType.STOP, context, history, null, null, null);
    }

    public HookType getType() { return type; }

    /** 应用上下文：UI / 配置 / 会话 / 工具注册表等。 */
    public ThoughtCodingContext getContext() { return context; }

    /** 最常用的输出能力快捷方式，等价于 {@code getContext().getUi()}。 */
    public ThoughtCodingUI getUi() { return context != null ? context.getUi() : null; }

    public List<ChatMessage> getHistory() { return history; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public ToolCall getToolCall() { return toolCall; }
    public ToolResult getToolResult() { return toolResult; }

    /** 注入附加上下文（USER_PROMPT_SUBMIT 时机专用）。 */
    public void injectContext(String context) {
        if (context != null && !context.isBlank()) {
            injectedContext.add(context);
        }
    }

    public List<String> getInjectedContext() {
        return new ArrayList<>(injectedContext);
    }
}
