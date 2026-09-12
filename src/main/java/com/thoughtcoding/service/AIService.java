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

    /** 当前是否存在可取消的主 Agent 流式生成。 */
    default boolean isGenerating() {
        return false;
    }

    /**
     * 请求停止当前主 Agent 生成。默认实现为空，具体 provider 可覆盖；
     * Runtime 关闭时通过接口调用，不依赖某个 AIService 实现类。
     */
    default void stopCurrentGeneration() {
    }

    /**
     * 🔥 子Agent专用：一次「隔离」的模型往返，不触碰共享的 messageHandler/toolCallHandler/生成状态。
     *
     * <p>供 {@code SubAgent} 在独立的子Agent历史上驱动循环使用。实现须：
     * <ul>
     *   <li>只读 {@code history}（本方法不改写它，由调用方 SubAgent 维护配对）；</li>
     *   <li>把工具规格里的 {@code subAgent} 过滤掉（子Agent看不到它，无从递归派生）；</li>
     *   <li>自行兜底任何超时/错误，返回一个「无工具调用」的结论文本，<b>永不抛出</b>。</li>
     * </ul>
     *
     * @param systemPrompt 子Agent系统提示
     * @param history      子Agent自己的对话历史（只读）
     * @param tokenSink    流式 token 回调，用于实时显示（可为 null）
     * @return 本轮结果（文本 + 工具请求）
     */
    default SubagentTurn chatOnceForSubagent(String systemPrompt, List<ChatMessage> history,
                                             Consumer<String> tokenSink) {
        throw new UnsupportedOperationException("chatOnceForSubagent not supported by this AIService");
    }

    /**
     * 同 {@link #chatOnceForSubagent(String, List, Consumer)}，但支持协作式取消：
     * token 触发时停止消费流式 token 并立即返回「被取消」的兜底结论（同样永不抛出）。
     * token 为 null 等价于不可取消。
     */
    default SubagentTurn chatOnceForSubagent(String systemPrompt, List<ChatMessage> history,
                                             Consumer<String> tokenSink,
                                             com.thoughtcoding.core.CancelToken token) {
        return chatOnceForSubagent(systemPrompt, history, tokenSink);
    }

    /**
     * 带取消令牌的流式对话：token 触发时提前结束等待（底层 HTTP 流由 SDK 自然收尾），
     * 已生成的部分文本仍会进 history。token 为 null 等价于不可取消。
     */
    default List<ChatMessage> streamingChat(String input, List<ChatMessage> history,
                                            String modelName,
                                            com.thoughtcoding.core.CancelToken token) {
        return streamingChat(input, history, modelName);
    }

    /**
     * 同 {@link #streamingChat(String, List, String, CancelToken)}，另带本轮召回的记忆正文：
     * 由调用方（AgentLoop）沿调用链请求局部传递并注入消息尾部，不落共享可变状态，
     * 避免并行/后台回合串写。recalledMemories 为 null/blank 等价于无召回。
     */
    default List<ChatMessage> streamingChat(String input, List<ChatMessage> history,
                                            String modelName,
                                            com.thoughtcoding.core.CancelToken token,
                                            String recalledMemories) {
        return streamingChat(input, history, modelName, token);
    }
}
