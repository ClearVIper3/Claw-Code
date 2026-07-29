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
     * 🔥 子代理专用：一次「隔离」的模型往返，不触碰共享的 messageHandler/toolCallHandler/生成状态。
     *
     * <p>供 {@code SubAgent} 在独立的子代理历史上驱动循环使用。实现须：
     * <ul>
     *   <li>只读 {@code history}（本方法不改写它，由调用方 SubAgent 维护配对）；</li>
     *   <li>把工具规格里的 {@code subAgent} 过滤掉（子代理看不到它，无从递归派生）；</li>
     *   <li>自行兜底任何超时/错误，返回一个「无工具调用」的结论文本，<b>永不抛出</b>。</li>
     * </ul>
     *
     * @param systemPrompt 子代理系统提示
     * @param history      子代理自己的对话历史（只读）
     * @param tokenSink    流式 token 回调，用于实时显示（可为 null）
     * @return 本轮结果（文本 + 工具请求）
     */
    default SubagentTurn chatOnceForSubagent(String systemPrompt, List<ChatMessage> history,
                                             Consumer<String> tokenSink) {
        throw new UnsupportedOperationException("chatOnceForSubagent not supported by this AIService");
    }
}