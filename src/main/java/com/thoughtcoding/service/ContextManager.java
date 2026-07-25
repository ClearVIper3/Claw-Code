package com.thoughtcoding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器
 * 负责管理对话历史的长度，防止 Token 超限
 *
 * 支持两种策略：
 * 1. 滑动窗口：保留最近 N 轮对话
 * 2. Token 控制：根据 Token 数量动态截断
 */
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final AppConfig appConfig;

    // 默认配置
    private static final int DEFAULT_MAX_HISTORY_TURNS = 10;  // 保留10轮（20条消息）
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 1000000;  // 为历史预留1M tokens
    private static final int DEFAULT_RESERVE_TOKENS = 1000;  // 为响应预留1000 tokens
    private static final int DEFAULT_KEEP_RECENT = 3; // 保留3轮（三轮以上的tool_result将被清除）

    // 策略枚举
    public enum Strategy {
        SLIDING_WINDOW,  // 滑动窗口
        TOKEN_BASED,     // 基于 Token
        HYBRID           // 混合策略
    }

    private Strategy strategy = Strategy.TOKEN_BASED;  // 默认使用 Token 控制
    private int maxHistoryTurns = DEFAULT_MAX_HISTORY_TURNS;
    private int maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;

    private static final Path TRANSCRIPT_DIR = Paths.get("transcripts");
    private final ObjectMapper objectMapper;

    private OpenAiChatModel ChatModel;

    public ContextManager(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        initializeChatModel();
        loadConfiguration();
    }

    /**
     * 从配置加载参数
     */
    private void loadConfiguration() {
        // TODO: 从 config.yaml 读取配置
        // 目前使用默认值
    }

    private void initializeChatModel() {
        try {
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig != null) {
                this.ChatModel = createDeepSeekModel(modelConfig);
            }
        } catch (Exception e) {
            System.err.println("初始化模型失败: " + e.getMessage());
        }
    }

    private OpenAiChatModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 获取适合发送给 AI 的上下文
     * 应用历史长度限制策略
     *
     * @param fullHistory 完整的对话历史
     * @return 经过处理的历史（不超过限制）
     */
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChatMessage> result;

        //自动压缩超过三轮的工具调用结果
        List<ChatMessage> afterMicro = micro_compact(fullHistory);

        switch (strategy) {
            case SLIDING_WINDOW:
                result = applySlidingWindow(afterMicro);
                break;
            case TOKEN_BASED:
                result = applyTokenLimit(fullHistory, afterMicro);
                break;
            case HYBRID:
                result = applyHybridStrategy(fullHistory, afterMicro);
                break;
            default:
                result = afterMicro;
        }

        // 输出统计信息
        logContextStatistics(fullHistory, result);

        // 🔥 保证发给模型的历史工具调用/结果配对一致（防止压缩裁剪导致孤立 id → 模型 400）
        return sanitizeToolPairs(result);
    }

    /**
     * 构建固定的项目上下文消息（原生 function calling 的简短系统提示），每次 AI 调用注入。
     *
     * @return 系统消息，如果无法获取则返回 null
     */
    public ChatMessage buildProjectContextMessage() {
        try {
            String cwd = System.getProperty("user.dir");
            if (cwd == null || cwd.isEmpty()) {
                return null;
            }
            return new ChatMessage("system", buildNativeSystemPrompt(cwd));
        } catch (Exception e) {
            log.warn("无法构建项目上下文: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 🔥 原生 function calling 的简短系统提示：只讲角色/语言/规则；
     * 不再罗列工具——工具的名称/说明/参数已由 ToolRegistry.getToolSpecifications() 原生注入给模型。
     */
    private String buildNativeSystemPrompt(String cwd) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 指令\n");
        sb.append("- 始终用中文回答用户的所有问题，解释与代码注释也用中文。\n");
        sb.append("- 你是一位资深编程助手（类似 Claude Code），可调用工具完成任务。\n\n");

        sb.append("## 工作环境\n");
        sb.append("工作目录: ").append(cwd).append("\n");
        sb.append("路径支持：相对路径、绝对路径、~ 用户主目录、.. 上级目录。\n\n");
        sb.append(buildSystemInfo());
        sb.append("\n");

        sb.append("## 规则\n");
        sb.append("1. 需要操作时直接调用系统提供的工具（其名称/说明/参数已由系统注入），不要把工具名或命令写进普通文本，也不要编造工具结果。\n");
        sb.append("2. 改动已有文件优先用 edit；新建/覆盖用 write；读文件用 read；跑命令或搜索内容用 bash。\n");
        sb.append("3. 只在确有需要时调用工具；纯咨询类问题直接用中文回答，不调用工具。\n");
        sb.append("4. 完成任务后用简洁自然的中文给出总结。\n");
        return sb.toString();
    }

    private String buildSystemInfo() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        StringBuilder sb = new StringBuilder();
        sb.append("操作系统: ").append(System.getProperty("os.name")).append("\n");
        if (isWindows) {
            sb.append("当前使用的 shell 为 PowerShell。\n");
        } else {
            sb.append("当前使用的 shell 为 bash。\n");
        }
        return sb.toString();
    }

    /**
     * 🔥 保证发给模型的历史中工具调用/结果配对一致（无论压缩如何裁剪）：
     *  - 丢弃没有对应 assistant 工具调用的孤立 role=tool 结果；
     *  - assistant 消息里剥掉没有对应结果的 toolCalls（非破坏性：修改副本，不动原始历史）。
     * 违反 "assistant 工具调用必须紧跟同 id 的 tool 结果" 会导致模型 400。
     */
    private List<ChatMessage> sanitizeToolPairs(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        java.util.Set<String> resultIds = new java.util.HashSet<>();
        java.util.Set<String> callIds = new java.util.HashSet<>();
        for (ChatMessage m : history) {
            if (m.isToolMessage() && m.getToolCallId() != null) {
                resultIds.add(m.getToolCallId());
            }
            if (m.getToolCalls() != null) {
                for (com.thoughtcoding.model.ToolCallRef r : m.getToolCalls()) {
                    if (r.getId() != null) callIds.add(r.getId());
                }
            }
        }

        List<ChatMessage> out = new ArrayList<>(history.size());
        for (ChatMessage m : history) {
            if (m.isToolMessage()) {
                if (m.getToolCallId() == null || !callIds.contains(m.getToolCallId())) {
                    continue; // 孤立工具结果 → 丢弃
                }
                out.add(m);
            } else if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<com.thoughtcoding.model.ToolCallRef> kept = new ArrayList<>();
                for (com.thoughtcoding.model.ToolCallRef r : m.getToolCalls()) {
                    if (r.getId() != null && resultIds.contains(r.getId())) {
                        kept.add(r);
                    }
                }
                if (kept.size() == m.getToolCalls().size()) {
                    out.add(m); // 全部有结果，原样保留
                } else {
                    ChatMessage copy = new ChatMessage(m); // 非破坏性：改副本
                    copy.setToolCalls(kept.isEmpty() ? null : kept);
                    out.add(copy);
                }
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private List<ChatMessage> micro_compact(List<ChatMessage> messages) {
        // sessions中保存结果不变，不可修改messages，使用深拷贝：创建全新消息对象
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            result.add(new ChatMessage(msg)); // 使用复制构造器
        }

        // 收集工具结果消息（在 result 中）：原生 role=tool 或旧的 role=system + "Tool '" 前缀
        List<ChatMessage> toolResults = new ArrayList<>();
        for (ChatMessage msg : result) {
            if (msg == null) continue;
            boolean isNativeToolResult = msg.isToolMessage();
            boolean isLegacyToolResult = "system".equals(msg.getRole()) && isToolResultMessage(msg.getContent());
            if (isNativeToolResult || isLegacyToolResult) {
                toolResults.add(msg);
            }
        }

        int KEEP_RECENT = DEFAULT_KEEP_RECENT;
        if (toolResults.size() <= KEEP_RECENT) {
            return result; // 不需要压缩，返回深拷贝副本
        }

        // 压缩早期的工具结果（除了最后 KEEP_RECENT 条）——只截断内容，不删除消息、不动 role/toolCallId，保持配对
        List<ChatMessage> toCompact = toolResults.subList(0, toolResults.size() - KEEP_RECENT);
        for (ChatMessage msg : toCompact) {
            String content = msg.getContent();
            if (content != null && content.length() > 100) {
                String toolName = (msg.isToolMessage() && msg.getToolName() != null)
                        ? msg.getToolName() : extractToolNameFromContent(content);
                String summary = String.format("[Previous: used %s]", toolName);
                msg.setContent(summary);
            }
        }

        return result;
    }

    private boolean isToolResultMessage(String content) {
        if (content == null) return false;
        // 工具成功或失败消息的特征前缀（兼容旧会话）
        return content.startsWith("Tool '") || content.startsWith("Tool execution failed: ");
    }

    // 从消息内容中提取工具名称
    // 格式示例: "Tool 'read_file' executed successfully ..." 或 "Tool execution failed: 'unknown_tool' not found."
    private String extractToolNameFromContent(String content) {
        try {
            // 查找单引号之间的内容
            int start = content.indexOf('\'');
            int end = content.indexOf('\'', start + 1);
            if (start != -1 && end != -1) {
                return content.substring(start + 1, end);
            }
            // 降级处理：尝试匹配 "Tool execution failed: " 后的内容
            if (content.startsWith("Tool execution failed: ")) {
                String after = content.substring("Tool execution failed: ".length());
                int space = after.indexOf(' ');
                if (space > 0) {
                    return after.substring(0, space);
                }
                return "unknown";
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 策略1：滑动窗口
     * 保留最近 N 轮对话
     */
    private List<ChatMessage> applySlidingWindow(List<ChatMessage> fullHistory) {
        int maxMessages = maxHistoryTurns * 2;  // 每轮包含用户+AI消息

        if (fullHistory.size() <= maxMessages) {
            return new ArrayList<>(fullHistory);
        }

        // 保留最近 N 条消息
        int startIndex = fullHistory.size() - maxMessages;
        return new ArrayList<>(fullHistory.subList(startIndex, fullHistory.size()));
    }

    /**
     * 策略2：Token 控制
     * 根据 Token 数量动态截断
     */
    private List<ChatMessage> applyTokenLimit(List<ChatMessage> fullHistory, List<ChatMessage> afterMicro) {
        int totalTokens = 0;

        for (int i = 0; i < afterMicro.size(); i++) {
            ChatMessage msg = afterMicro.get(i);
            int msgTokens = estimateTokens(msg.getContent());

            totalTokens += msgTokens;
        }

        if (totalTokens < maxContextTokens) {
            return afterMicro;
        }

        try {
            // 1. 生成对话文本
            // 精准切分：保留最后 2 条记录（通常是最后一轮 User 问 + AI 答）
            int keepCount = Math.min(2, fullHistory.size());
            int splitIndex = fullHistory.size() - keepCount;

            List<ChatMessage> toSummarize = fullHistory.subList(0, splitIndex);
            List<ChatMessage> tailMessages = new ArrayList<>(fullHistory.subList(splitIndex, fullHistory.size()));

            String conversationText = truncateConversation(toSummarize);

            // 2. 构建摘要 prompt
            String prompt = "Summarize this conversation for continuity. Include: " +
                    "1) What was accomplished, 2) Current state, 3) Key decisions made. " +
                    "Be concise but preserve critical details.\n\n" + conversationText;

            // 3. 调用 LLM 生成摘要
            String summary = callLlmForSummary(prompt);

            // 4. 构建压缩后的消息列表
            String sessionId = afterMicro.get(0).getSessionId();
            fullHistory.clear();

            // 构建新的消息历史
            fullHistory.add(new ChatMessage("user",
                    "[Conversation compressed.]" + "\n\n" + summary, sessionId));

            // 重新接上尾部对话，保证上下文连贯
            fullHistory.addAll(tailMessages);

            return fullHistory;
        } catch (Exception e) {
            return afterMicro;
        }
    }

    /**
     * 策略3：混合策略
     * 先应用滑动窗口，再应用 Token 控制
     */
    private List<ChatMessage> applyHybridStrategy(List<ChatMessage> fullHistory, List<ChatMessage> afterMicro) {
        // 1. 先应用滑动窗口
        List<ChatMessage> windowedHistory = applySlidingWindow(fullHistory);

        // 2. 再应用 Token 控制
        return applyTokenLimit(windowedHistory, afterMicro);
    }

    /**
     * 估算文本的 Token 数量
     * 简单方法：中文 2 字符 ≈ 1 token，英文 4 字符 ≈ 1 token
     *
     * @param text 待估算的文本
     * @return 估算的 token 数量
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int otherChars = 0;

        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }

        // 中文：2 字符 ≈ 1 token
        // 英文：4 字符 ≈ 1 token
        return (chineseChars / 2) + (otherChars / 4);
    }

    /**
     * 判断字符是否为中文
     */
    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /**
     * 截断文本到指定 Token 限制
     */
    private String truncateToTokenLimit(String text, int maxTokens) {
        if (estimateTokens(text) <= maxTokens) {
            return text;
        }

        // 简单截断：取前 N 个字符
        int targetChars = maxTokens * 3;  // 保守估计
        if (text.length() <= targetChars) {
            return text;
        }

        return text.substring(0, targetChars) + "\n\n[内容过长已截断...]";
    }

    /**
     * 输出上下文统计信息
     */
    private void logContextStatistics(List<ChatMessage> fullHistory, List<ChatMessage> managedHistory) {
        int fullTokens = fullHistory.stream()
                .mapToInt(msg -> estimateTokens(msg.getContent()))
                .sum();

        int managedTokens = managedHistory.stream()
                .mapToInt(msg -> estimateTokens(msg.getContent()))
                .sum();

        if (fullHistory.size() != managedHistory.size()) {
            log.debug("📊 上下文管理统计:");
            log.debug("  完整历史: {} 条消息 (~{} tokens)", fullHistory.size(), fullTokens);
            log.debug("  发送历史: {} 条消息 (~{} tokens)", managedHistory.size(), managedTokens);
            log.debug("  节省: {} tokens ({}%)",
                    fullTokens - managedTokens,
                    (fullTokens - managedTokens) * 100 / Math.max(fullTokens, 1));
        }
    }

    /**
     * 将消息列表转换为 JSON 字符串（用于传给 LLM）
     */
    private String truncateConversation(List<ChatMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize messages", e);
        }
    }

    private String callLlmForSummary(String prompt) {
        ChatResponse response = ChatModel.chat(UserMessage.from(prompt));
        return response.aiMessage().text();
    }

    /**
     * 获取当前策略
     */
    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * 设置策略
     */
    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
        log.info("切换上下文策略为: {}", strategy);
    }

    /**
     * 设置最大历史轮数（用于滑动窗口策略）
     */
    public void setMaxHistoryTurns(int maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
        log.info("设置最大历史轮数: {} 轮", maxHistoryTurns);
    }

    /**
     * 设置最大上下文 Token 数
     */
    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
        log.info("设置最大上下文 Tokens: {}", maxContextTokens);
    }

    /**
     * 获取配置摘要
     */
    public String getConfigSummary() {
        return String.format("Strategy: %s, MaxTurns: %d, MaxTokens: %d",
                strategy, maxHistoryTurns, maxContextTokens);
    }
}
