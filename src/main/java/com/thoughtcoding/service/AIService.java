package com.thoughtcoding.service;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SubagentTurn;
import com.thoughtcoding.model.ToolCall;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 服务接口，定义了与 AI 模型交互的方法
 */
public interface AIService {
    List<ChatMessage> chat(String input, List<ChatMessage> history, String modelName);
    List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName);
    void setMessageHandler(Consumer<ChatMessage> handler);
    void setToolCallHandler(Consumer<ToolCall> handler);
    boolean validateModel(String modelName);
    List<String> getAvailableModels();

    /**
     * 🔥 隔离原语（teammate 专用）：一次「隔离」的模型往返，不触碰共享的 messageHandler/toolCallHandler/生成状态。
     *
     * <p>供 {@code Teammate} 在独立的队友历史上驱动循环使用。实现须：
     * <ul>
     *   <li>只读 {@code history}（本方法不改写它，由调用方 Teammate 维护配对）；</li>
     *   <li>把工具规格里属于 {@code excludedToolNames} 的名字过滤掉（队友看不到它们，
     *       从而无法再派生队友/子Agent —— 防递归的唯一手段）；</li>
     *   <li>自行兜底任何超时/错误，返回一个「无工具调用」的结论文本，<b>永不抛出</b>。</li>
     * </ul>
     *
     * @param systemPrompt     系统提示（队友系统提示）
     * @param history          队友自己的对话历史（只读）
     * @param tokenSink        流式 token 回调，用于实时显示（可为 null；后台队友应传 null 保持静默）
     * @param excludedToolNames 需从工具规格中过滤掉的工具名集合（null = 不过滤）
     * @return 本轮结果（文本 + 工具请求）
     */
    default SubagentTurn chatOnceIsolated(String systemPrompt, List<ChatMessage> history,
                                          Consumer<String> tokenSink,
                                          java.util.Set<String> excludedToolNames) {
        throw new UnsupportedOperationException("chatOnceIsolated not supported by this AIService");
    }
}