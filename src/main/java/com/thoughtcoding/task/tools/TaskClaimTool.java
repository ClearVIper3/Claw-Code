package com.thoughtcoding.task.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.task.Task;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Map;

/**
 * task_claim 工具：认领任务（带依赖守卫）。
 * 仅 pending 且 blockedBy 全部 completed（canStart）的任务可认领；被阻塞或状态非 pending 报错并列出缺失依赖。
 */
public class TaskClaimTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskStore store;

    public TaskClaimTool(TaskStore store) {
        super("task_claim",
                "认领一个任务，将其从 pending 置为 in_progress 并设 owner。\n"
                + "何时使用：决定开始做某个任务时。\n"
                + "用法：id（任务 id，必填）、owner（负责人，可选，默认 agent）。\n"
                + "依赖守卫：该任务的 blockedBy 依赖必须全部 completed 才能认领，否则报错并列出缺失依赖。");
        this.store = store;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("id", "任务 id")
                .addStringProperty("owner", "负责人（可选，默认 agent）")
                .required("id")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);
            Object idObj = params.get("id");
            if (idObj == null || idObj.toString().isBlank()) {
                return error("task_claim 需要 'id'", System.currentTimeMillis() - startTime);
            }
            String id = idObj.toString().trim();
            String owner = params.get("owner") == null ? "agent" : params.get("owner").toString();

            Task t = store.claim(id, owner);
            if (t == null) {
                Task cur = store.get(id);
                if (cur == null) {
                    return error("任务 #" + id + " 不存在", System.currentTimeMillis() - startTime);
                }
                if (!"pending".equals(cur.getStatus())) {
                    return error("任务 #" + id + " 状态为 " + cur.getStatus() + "，无法认领（仅 pending 可认领）",
                            System.currentTimeMillis() - startTime);
                }
                List<String> missing = store.missingDependencies(id);
                return error("任务 #" + id + " 被依赖阻塞，缺少已完成依赖: " + String.join(", ", missing),
                        System.currentTimeMillis() - startTime);
            }
            return success("已认领 #" + t.getId() + " " + t.getSubject() + " (owner: " + owner + ")\n" + store.render(),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("task_claim 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
