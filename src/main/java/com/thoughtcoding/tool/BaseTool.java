package com.thoughtcoding.tool;

import com.thoughtcoding.model.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * 工具的抽象基类，定义了工具的基本属性和行为
 *
 * <p><b>新增只读工具时注意：</b>若该工具的返回内容体积大、只是给模型用的（如读文件/查目录/加载技能正文），
 * 记得把工具名加进 {@code AgentLoop.QUIET_OUTPUT_TOOLS}，否则完整内容会 dump 到用户终端刷屏。
 * 该集合决定“结果只回喂模型、不在用户端显示”。（漏加不影响正确性，只是变啰嗦。）
 */
public abstract class BaseTool {
    protected final String name;
    protected final String description;

    public BaseTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public abstract ToolResult execute(String input);

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 🔥 获取输入参数的Schema定义（用于MCP工具）
     * 子类可以重写此方法以提供参数schema信息给AI
     */
    public Object getInputSchema() {
        return null; // 默认返回null，MCP工具可以重写
    }

    /**
     * 工具自带的参数 schema（内置工具覆写，与 name/description/execute 同处一个类）。
     * 返回 null 表示无自带 schema —— 例如 MCP 工具改用 {@link #getInputSchema()} 的原始 JSON schema。
     */
    public JsonObjectSchema inputSchema() {
        return null;
    }

    /**
     * 是否只读工具（默认 false）。对齐 Claude Code 的 Tool 契约：内置只读工具（read/glob/skill…）
     * override 为 true；MCP 工具由 {@code tools/list} 的 annotations.readOnlyHint 填充。
     * <p>当前仅作元数据（供展示/日后并发调度用），<b>不参与审批决策</b>——审批仍走 PermissionGate。
     */
    public boolean isReadOnly() {
        return false;
    }

    /**
     * 是否可能产生不可逆副作用（删除/覆盖/发送，默认 false）。
     * MCP 工具由 annotations.destructiveHint 填充。同样仅作元数据。
     */
    public boolean isDestructive() {
        return false;
    }

    protected ToolResult success(String output) {
        return ToolResult.success(output, 0);
    }

    protected ToolResult error(String error) {
        return ToolResult.error(error, 0);
    }

    protected ToolResult success(String output, long executionTime) {
        return ToolResult.success(output, executionTime);
    }

    protected ToolResult error(String error, long executionTime) {
        return ToolResult.error(error, executionTime);
    }

    /** 展开路径中的 ~ 为用户主目录（~ 或 ~/xxx）。 */
    protected String expandUserHome(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    @Override
    public String toString() {
        return String.format("Tool{name=%s, description=%s}", name, description);
    }
}