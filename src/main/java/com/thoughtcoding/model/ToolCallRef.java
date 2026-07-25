package com.thoughtcoding.model;

/**
 * 一条 langchain4j ToolExecutionRequest 的可序列化载体。
 *
 * 承载在 assistant 角色的 {@link ChatMessage} 上，用于在下一轮对话时
 * 重建 AiMessage.toolExecutionRequests，从而满足 "每个 tool 结果前必有携带同 id 的 assistant 工具调用" 的模型契约。
 */
public class ToolCallRef {
    private String id;          // = ToolExecutionRequest.id()
    private String name;        // 工具名
    private String arguments;   // 参数 JSON 字符串

    public ToolCallRef() {
    }

    public ToolCallRef(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }

    @Override
    public String toString() {
        return String.format("ToolCallRef{id=%s, name=%s}", id, name);
    }
}
