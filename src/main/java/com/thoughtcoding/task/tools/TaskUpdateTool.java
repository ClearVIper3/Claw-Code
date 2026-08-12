package com.thoughtcoding.task.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

/**
 * task_update 工具：编辑任务字段/依赖边/删除/异常状态修正。
 *
 * <p>职责边界：本工具是<b>无守卫</b>的字段编辑入口——subject/description/owner 编辑、依赖边增删
 * （addBlockedBy/addBlocks）、status=deleted 删除、以及非常规状态修正（逃生通道）。
 * 正常的状态机推进（pending→in_progress→completed）请用 task_claim / task_complete，它们带依赖守卫。
 */
public class TaskUpdateTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed", "deleted");

    private final TaskStore store;

    public TaskUpdateTool(TaskStore store) {
        super("task_update",
                "更新任务字段、依赖边或删除任务。\n"
                + "何时使用：修改任务描述/owner、调整 blockedBy 依赖（addBlockedBy 追加依赖、addBlocks 让目标任务依赖本任务）、"
                + "删除任务（status=deleted）、或修正异常状态（逃生通道）时。\n"
                + "用法：id（必填）+ 任意可选字段。注意：正常的 pending→in_progress→completed 推进请用 task_claim/task_complete，"
                + "它们会做依赖检查；本工具的 status 只用于非常规修正。");
        this.store = store;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        JsonArraySchema depArray = JsonArraySchema.builder()
                .description("任务 id 数组")
                .items(JsonStringSchema.builder().build())
                .build();
        return JsonObjectSchema.builder()
                .addStringProperty("id", "要更新的任务 id")
                .addStringProperty("subject", "新任务描述（可选）")
                .addStringProperty("description", "新任务详情（可选）")
                .addStringProperty("owner", "负责人（可选）")
                .addProperty("addBlockedBy", depArray)
                .addProperty("addBlocks", depArray)
                .addEnumProperty("status", STATUSES,
                        "状态（可选）：pending/in_progress/completed/deleted。正常推进请用 task_claim/task_complete")
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
                return error("task_update 需要 'id'", System.currentTimeMillis() - startTime);
            }
            String id = idObj.toString().trim();

            String subject = params.get("subject") == null ? null : params.get("subject").toString();
            String description = params.get("description") == null ? null : params.get("description").toString();
            String owner = params.get("owner") == null ? null : params.get("owner").toString();
            List<String> addBlockedBy = TaskCreateTool.stringList(params.get("addBlockedBy"));
            List<String> addBlocks = TaskCreateTool.stringList(params.get("addBlocks"));
            String status = params.get("status") == null ? null : params.get("status").toString();

            if (store.update(id, subject, description, owner, addBlockedBy, addBlocks, status) == null) {
                return error("任务 #" + id + " 不存在（或已删除）", System.currentTimeMillis() - startTime);
            }
            String rendered = store.render();
            // 逃生通道（status=completed）也可能完成最后一个任务：整图完成后同样清空 .tasks/
            if (store.allCompleted()) {
                store.clearAll();
                rendered += "\n🧹 全部任务已完成，已清空任务图（.tasks/ 重置）。";
            }
            return success("已更新 #" + id + "\n" + rendered, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("task_update 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
