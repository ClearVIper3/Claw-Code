package com.thoughtcoding.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 *  聊天消息模型，包含消息ID、角色、内容和时间戳
 *
 *  角色 role: "user" / "assistant" / "system" / "tool"（原生工具结果）。
 */
@Data
public class ChatMessage {
    private final String id;
    private final String role; // "user", "assistant", "system", "tool"
    private String content; //压缩工具结果需要其为变量
    private final String timestamp;
    private final String sessionId;

    // 🔥 原生工具调用支持（均可空，旧消息/旧会话为 null）
    private String toolCallId;              // role=tool 时：对应的工具调用 id（= ToolExecutionRequest.id）
    private String toolName;                // role=tool 时：工具名
    private List<ToolCallRef> toolCalls;    // role=assistant 且请求工具时：本条 assistant 消息发起的工具调用
    @JsonIgnore  // 忽略这些字段的序列化
    private boolean userMessage;

    @JsonIgnore
    private boolean systemMessage;

    @JsonIgnore
    private boolean assistantMessage;
    // 无参构造函数 - 需要提供默认值
    public ChatMessage() {
        this.role = "user";
        this.content = "";
        this.sessionId = null;
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toString();
    }


    public ChatMessage(String role, String content) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.timestamp = String.valueOf(LocalDateTime.now());
        this.sessionId = null;
    }

    public ChatMessage(String role, String content, String sessionId) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.timestamp = String.valueOf(LocalDateTime.now());
        this.sessionId = sessionId;
    }

    //深拷贝构造器
    public ChatMessage(ChatMessage other) {
        this.id = other.id;
        this.role = other.role;
        this.content = other.content;
        this.timestamp = other.timestamp;
        this.sessionId = other.sessionId;
        // 🔥 工具字段也必须带上，否则压缩深拷贝会丢失工具调用/结果的配对信息
        this.toolCallId = other.toolCallId;
        this.toolName = other.toolName;
        this.toolCalls = other.toolCalls;
    }

    // 添加静态工厂方法
    public static ChatMessage from(String content) {
        return new ChatMessage("assistant", content); // 默认角色为 "assistant"
    }

    /** 🔥 构造一条 assistant 消息，携带其发起的工具调用（text 可为空） */
    public static ChatMessage assistantWithToolCalls(String text, List<ToolCallRef> toolCalls) {
        ChatMessage msg = new ChatMessage("assistant", text == null ? "" : text);
        msg.toolCalls = toolCalls;
        return msg;
    }

    /** 🔥 构造一条工具结果消息（role=tool），按 toolCallId 与发起的工具调用配对 */
    public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
        ChatMessage msg = new ChatMessage("tool", content == null ? "" : content);
        msg.toolCallId = toolCallId;
        msg.toolName = toolName;
        return msg;
    }

    // Getters
    public String getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getTimestamp() { return timestamp; }
    public String getSessionId() { return sessionId; }

    // 🔥 工具字段访问器（此代码库 lombok @Data 未生效，需显式声明）
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public List<ToolCallRef> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallRef> toolCalls) { this.toolCalls = toolCalls; }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s", timestamp, role, content);
    }

    public boolean isUserMessage() {
        return "user".equals(role);
    }

    public boolean isAssistantMessage() {
        return "assistant".equals(role);
    }

    public boolean isSystemMessage() {
        return "system".equals(role);
    }

    /** 🔥 是否为原生工具结果消息 */
    public boolean isToolMessage() {
        return "tool".equals(role);
    }

    /** 🔥 是否为发起了工具调用的 assistant 消息 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public void setRole() {
        String role = "user";
    }

    public void setContent() {
        String content = "";
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setTimestamp(String s) {
        String toString = Instant.now().toString();
    }
}