package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SubagentTurn;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolCallRef;
import com.thoughtcoding.tool.ToolRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 基于 langchain4j 原生 function calling 的 AI 服务实现（DeepSeek / OpenAI 兼容）。
 *
 * 每次请求携带工具的 ToolSpecification；模型在 onCompleteResponse 返回结构化的
 * toolExecutionRequests，由 AgentLoop 执行并把结果按 id 配对回喂，形成 agentic 循环。
 */
public class LangChainService implements AIService {
    private final AppConfig appConfig;
    private final ContextManager contextManager;
    private final ToolRegistry toolRegistry;   // 用于生成 ToolSpecification
    private Consumer<ChatMessage> messageHandler;
    private Consumer<ToolCall> toolCallHandler;
    private StreamingChatModel streamingChatModel;

    // 生成状态
    private volatile boolean isGenerating = false;
    private volatile boolean shouldStop = false;

    public LangChainService(AppConfig appConfig, ToolRegistry toolRegistry, ContextManager contextManager) {
        this.appConfig = appConfig;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
        initializeChatModel();
    }

    private void initializeChatModel() {
        try {
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig != null) {
                this.streamingChatModel = createDeepSeekModel(modelConfig);
            }
        } catch (Exception e) {
            System.err.println("初始化模型失败: " + e.getMessage());
        }
    }

    private StreamingChatModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Override
    public List<ChatMessage> chat(String input, List<ChatMessage> history, String modelName) {
        throw new UnsupportedOperationException("Use streamingChat for real AI service");
    }

    @Override
    public List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName) {
        return streamingChat(input, history, modelName, null);
    }

    @Override
    public List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName,
                                           com.thoughtcoding.core.CancelToken token) {
        if (messageHandler == null) {
            throw new IllegalStateException("Message handler not set");
        }
        if (streamingChatModel == null) {
            throw new IllegalStateException("DeepSeek model not initialized. Please check your configuration.");
        }

        isGenerating = true;
        shouldStop = false;

        final StringBuilder fullResponse = new StringBuilder();
        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        // 取消传播：token 触发时提前完成 future，让下面的 get() 立即返回。
        // 已知限制：langchain4j 不暴露底层 okhttp 调用的取消，HTTP 流由 SDK 自然收尾；
        // 迟到的 onCompleteResponse 仍可能往 history 追加消息（无害，仅本轮不使用）。
        if (token != null) {
            token.onCancel(() -> completionFuture.complete(null));
        }

        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = prepareMessages(input, history);

            streamingChatNative(messages, history, fullResponse, completionFuture);

            // 等待流式响应完成（最多 5 分钟）
            try {
                completionFuture.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("⚠️  流式响应超时");
                completionFuture.cancel(true);
            } catch (Exception e) {
                System.err.println("⚠️  等待流式响应时发生错误: " + e.getMessage());
            }

        } catch (Exception e) {
            isGenerating = false;
            shouldStop = false;
            System.err.println("❌ Service error: " + e.getMessage());
            ChatMessage errorMessage = new ChatMessage("assistant",
                    "服务暂时不可用，请稍后重试。错误信息: " + e.getMessage());
            messageHandler.accept(errorMessage);
            history.add(errorMessage);
        }

        return history;
    }

    /**
     * 原生 function calling：带 ToolSpecification 发起请求，在 onCompleteResponse 读取结构化工具请求。
     */
    private void streamingChatNative(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            List<ChatMessage> history,
            StringBuilder fullResponse,
            CompletableFuture<Void> completionFuture) {

        dev.langchain4j.model.chat.request.ChatRequest.Builder reqBuilder =
                dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages);
        if (toolRegistry != null) {
            List<dev.langchain4j.agent.tool.ToolSpecification> specs = toolRegistry.getToolSpecifications();
            if (specs != null && !specs.isEmpty()) {
                reqBuilder.toolSpecifications(specs);
            }
        }
        dev.langchain4j.model.chat.request.ChatRequest request = reqBuilder.build();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (shouldStop) {
                    return;
                }
                fullResponse.append(token);
                if (messageHandler != null) {
                    messageHandler.accept(new ChatMessage("assistant", token));
                }
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse chatResponse) {
                try {
                    dev.langchain4j.data.message.AiMessage ai = chatResponse.aiMessage();
                    String text = ai.text();
                    List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests = ai.toolExecutionRequests();

                    if (requests != null && !requests.isEmpty()) {
                        // 携带工具调用的 assistant 消息加入 history（供下一轮重建 AiMessage.toolExecutionRequests）
                        List<ToolCallRef> refs = new ArrayList<>();
                        for (dev.langchain4j.agent.tool.ToolExecutionRequest req : requests) {
                            refs.add(new ToolCallRef(req.id(), req.name(), req.arguments()));
                        }
                        history.add(ChatMessage.assistantWithToolCalls(text, refs));

                        // 逐个工具请求发出 ToolCall（带 providerCallId），由 AgentLoop 累积并执行
                        if (toolCallHandler != null) {
                            for (dev.langchain4j.agent.tool.ToolExecutionRequest req : requests) {
                                java.util.Map<String, Object> params = parseArguments(req.arguments());
                                ToolCall call = new ToolCall(req.name(), params, null, false, 0, false, req.id());
                                toolCallHandler.accept(call);
                            }
                        }
                    } else {
                        // 纯文本回复
                        if (text != null && !text.isBlank()) {
                            history.add(new ChatMessage("assistant", text));
                        }
                    }
                } finally {
                    isGenerating = false;
                    shouldStop = false;
                    completionFuture.complete(null);
                }
            }

            @Override
            public void onError(Throwable error) {
                try {
                    System.err.println("❌ API error: " + error.getMessage());
                    ChatMessage errorMessage = new ChatMessage("assistant",
                            "抱歉，我在处理您的请求时遇到了问题： " + error.getMessage());
                    if (messageHandler != null) {
                        messageHandler.accept(errorMessage);
                    }
                    history.add(errorMessage);
                } finally {
                    isGenerating = false;
                    shouldStop = false;
                    completionFuture.completeExceptionally(error);
                }
            }
        });
    }

    /** 把工具调用参数 JSON 解析为 Map；失败则退化为 {"input": 原始串}。 */
    private java.util.Map<String, Object> parseArguments(String argumentsJson) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return params;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(argumentsJson, java.util.Map.class);
        } catch (Exception e) {
            params.put("input", argumentsJson);
            return params;
        }
    }

    /**
     * 🔥 子Agent专用：一次「隔离」的模型往返。
     *
     * <p>与 {@link #streamingChat} 的关键区别 —— <b>完全不触碰</b>本实例的共享可变状态
     * （{@code messageHandler}/{@code toolCallHandler}/{@code isGenerating}/{@code shouldStop}），
     * 也<b>不改写</b>传入的 history，且从工具规格里过滤掉 {@code subAgent} 以禁止子Agent递归。
     * 用本地 future 阻塞等待，任何超时/错误都兜底为一个「无工具调用」的结论文本，永不抛出。
     */
    @Override
    public SubagentTurn chatOnceForSubagent(String systemPrompt, List<ChatMessage> history,
                                            java.util.function.Consumer<String> tokenSink) {
        return chatOnceForSubagent(systemPrompt, history, tokenSink, null);
    }

    @Override
    public SubagentTurn chatOnceForSubagent(String systemPrompt, List<ChatMessage> history,
                                            java.util.function.Consumer<String> tokenSink,
                                            com.thoughtcoding.core.CancelToken token) {
        if (streamingChatModel == null) {
            return new SubagentTurn("(子Agent不可用：模型未初始化)", java.util.Collections.emptyList());
        }

        // 组装消息：子Agent系统提示 + 子Agent自己的历史（不走 getContextForAI 压缩，生命周期短）
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(dev.langchain4j.data.message.SystemMessage.from(systemPrompt));
        }
        if (history != null && !history.isEmpty()) {
            messages.addAll(convertToLangChainHistory(history));
        }

        dev.langchain4j.model.chat.request.ChatRequest.Builder reqBuilder =
                dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages);
        if (toolRegistry != null) {
            List<dev.langchain4j.agent.tool.ToolSpecification> specs = toolRegistry.getToolSpecifications();
            if (specs != null && !specs.isEmpty()) {
                // 过滤掉 subAgent 自身：子Agent看不到它，就无从递归派生（防递归的唯一手段）
                List<dev.langchain4j.agent.tool.ToolSpecification> filtered = new ArrayList<>();
                for (dev.langchain4j.agent.tool.ToolSpecification s : specs) {
                    if (!"subAgent".equals(s.name())) {
                        filtered.add(s);
                    }
                }
                if (!filtered.isEmpty()) {
                    reqBuilder.toolSpecifications(filtered);
                }
            }
        }
        dev.langchain4j.model.chat.request.ChatRequest request = reqBuilder.build();

        final CompletableFuture<SubagentTurn> future = new CompletableFuture<>();
        // 取消传播：token 触发时取消等待；流式回调幂等补完（complete 已取消的 future 是 no-op）
        if (token != null) {
            token.onCancel(() -> future.cancel(false));
        }
        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (token != null && token.isCancelled()) {
                    return; // 已取消：停止消费后续 token
                }
                if (tokenSink != null && partial != null) {
                    try {
                        tokenSink.accept(partial);
                    } catch (Exception ignored) {
                        // 显示回调异常不影响子Agent推进
                    }
                }
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse chatResponse) {
                dev.langchain4j.data.message.AiMessage ai = chatResponse.aiMessage();
                String text = ai.text();
                List<ToolCallRef> refs = new ArrayList<>();
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests = ai.toolExecutionRequests();
                if (requests != null) {
                    for (dev.langchain4j.agent.tool.ToolExecutionRequest req : requests) {
                        refs.add(new ToolCallRef(req.id(), req.name(), req.arguments()));
                    }
                }
                future.complete(new SubagentTurn(text, refs));
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        // 异常全包：绝不抛出（ToolDispatcher/SubAgent 依赖这一点保持工具配对不被破坏）
        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return new SubagentTurn("(子Agent调用超时)", java.util.Collections.emptyList());
        } catch (java.util.concurrent.CancellationException e) {
            return new SubagentTurn("(子Agent调用已被用户取消)", java.util.Collections.emptyList());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new SubagentTurn("(子Agent调用失败: " + cause.getMessage() + ")",
                    java.util.Collections.emptyList());
        }
    }

    private List<dev.langchain4j.data.message.ChatMessage> prepareMessages(
            String input, List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        if (contextManager != null) {
            ChatMessage projectContext = contextManager.buildProjectContextMessage();
            if (projectContext != null) {
                messages.add(dev.langchain4j.data.message.SystemMessage.from(projectContext.getContent()));
            }
        }

        List<ChatMessage> managedHistory = history;
        if (contextManager != null && history != null && !history.isEmpty()) {
            managedHistory = contextManager.getContextForAI(history);
        }

        if (managedHistory != null && !managedHistory.isEmpty()) {
            messages.addAll(convertToLangChainHistory(managedHistory));
        }

        // 纯从 history 渲染：用户消息已由 AgentLoop 加入 history；input=null 时供 agentic 循环复用
        return messages;
    }

    private List<dev.langchain4j.data.message.ChatMessage> convertToLangChainHistory(
            List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> out = new ArrayList<>();
        for (ChatMessage msg : history) {
            String role = msg.getRole();
            String content = msg.getContent();
            if ("user".equals(role)) {
                out.add(dev.langchain4j.data.message.UserMessage.from(content));
            } else if ("tool".equals(role)) {
                // 原生工具结果，按 id 与 assistant 工具调用配对
                out.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                        msg.getToolCallId(),
                        msg.getToolName(),
                        content == null ? "" : content));
            } else if ("assistant".equals(role)) {
                if (msg.hasToolCalls()) {
                    // 携带工具调用的 assistant 消息 → AiMessage(text, requests)
                    List<dev.langchain4j.agent.tool.ToolExecutionRequest> reqs = new ArrayList<>();
                    for (ToolCallRef ref : msg.getToolCalls()) {
                        reqs.add(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id(ref.getId())
                                .name(ref.getName())
                                .arguments(ref.getArguments() == null ? "{}" : ref.getArguments())
                                .build());
                    }
                    if (content == null || content.isBlank()) {
                        out.add(dev.langchain4j.data.message.AiMessage.from(reqs));
                    } else {
                        out.add(dev.langchain4j.data.message.AiMessage.from(content, reqs));
                    }
                } else {
                    out.add(dev.langchain4j.data.message.AiMessage.from(content));
                }
            } else {
                // system 及其它角色
                out.add(dev.langchain4j.data.message.SystemMessage.from(content));
            }
        }
        return out;
    }

    @Override
    public void setMessageHandler(Consumer<ChatMessage> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void setToolCallHandler(Consumer<ToolCall> handler) {
        this.toolCallHandler = handler;
    }

    @Override
    public boolean validateModel(String modelName) {
        return appConfig.getModels().containsKey(modelName);
    }

    @Override
    public List<String> getAvailableModels() {
        return new ArrayList<>(appConfig.getModels().keySet());
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    public void stopCurrentGeneration() {
        if (isGenerating) {
            shouldStop = true;
            System.out.println("⏸️  正在停止生成...");
        }
    }
}
