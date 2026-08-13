package com.thoughtcoding.worktree.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.worktree.GitWorktreeManager;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * remove_worktree 工具（Lead 侧，s18）：移除一个 worktree 及其分支。默认做安全检查——
 * 有未提交改动或未推送提交时拒绝；{@code discard_changes=true} 强制移除。
 */
public class RemoveWorktreeTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitWorktreeManager manager;

    public RemoveWorktreeTool(GitWorktreeManager manager) {
        super("remove_worktree",
                "移除一个 worktree 及其分支 wt/<name>。\n"
                + "默认安全：若该 worktree 有未提交改动或未推送提交则拒绝（并报告数量）；"
                + "确认要丢弃用 discard_changes=true 强制移除，想保留待审查用 keep_worktree。");
        this.manager = manager;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("name", "worktree 名")
                .addBooleanProperty("discard_changes", "有未提交改动/未推送提交时也强制移除（默认 false）")
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
                return error("remove_worktree 需要 'name'", System.currentTimeMillis() - startTime);
            }
            String name = nameObj.toString().trim();
            boolean discard = Boolean.TRUE.equals(params.get("discard_changes"))
                    || "true".equalsIgnoreCase(String.valueOf(params.get("discard_changes")));

            GitWorktreeManager.GitResult r = manager.removeWorktree(name, discard);
            return (r.ok()
                    ? success(r.output(), System.currentTimeMillis() - startTime)
                    : error(r.output(), System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            return error("remove_worktree 参数解析失败: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }
}
