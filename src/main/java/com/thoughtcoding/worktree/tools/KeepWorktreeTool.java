package com.thoughtcoding.worktree.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.worktree.GitWorktreeManager;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * keep_worktree 工具（Lead 侧，s18）：保留一个 worktree 待人工审查——目录与分支
 * {@code wt/<name>} 均不删除，仅记录一条 keep 事件。
 */
public class KeepWorktreeTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitWorktreeManager manager;

    public KeepWorktreeTool(GitWorktreeManager manager) {
        super("keep_worktree",
                "保留一个 worktree 待人工审查（目录与分支 wt/<name> 均保留，不删除）。\n"
                + "何时使用：队友产出想先人工过目再决定合并/移除时。");
        this.manager = manager;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("name", "worktree 名")
                .required("name")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);
            Object nameObj = params.get("name");
            if (nameObj == null || nameObj.toString().isBlank()) {
                return error("keep_worktree 需要 'name'", System.currentTimeMillis() - startTime);
            }
            String name = nameObj.toString().trim();

            GitWorktreeManager.GitResult r = manager.keepWorktree(name);
            return (r.ok()
                    ? success(r.output(), System.currentTimeMillis() - startTime)
                    : error(r.output(), System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            return error("keep_worktree 参数解析失败: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }
}
