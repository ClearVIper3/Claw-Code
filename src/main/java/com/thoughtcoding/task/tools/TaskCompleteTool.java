package com.thoughtcoding.task.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.task.Task;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * task_complete 工具：完成任务（带守卫，仅 in_progress 可完成）。
 * 完成后报告被解锁的下游任务（blockedBy 依赖本任务且现可开始）。
 */
public class TaskCompleteTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskStore store;

    public TaskCompleteTool(TaskStore store) {
        super("task_complete",
                "完成任务，将其从 in_progress 置为 completed。\n"
                + "何时使用：当前任务做完、要把进度推进到下一步时。\n"
                + "用法：id（任务 id，必填）。\n"
                + "守卫：仅 in_progress 可完成（先 task_claim）。完成后会报告被解锁的下游任务。");
        this.store = store;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("id", "任务 id")
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
                return error("task_complete 需要 'id'", System.currentTimeMillis() - startTime);
            }
            String id = idObj.toString().trim();

            Task t = store.complete(id);
            if (t == null) {
                Task cur = store.get(id);
                if (cur == null) {
                    return error("任务 #" + id + " 不存在", System.currentTimeMillis() - startTime);
                }
                return error("任务 #" + id + " 状态为 " + cur.getStatus() + "，无法完成（仅 in_progress 可完成，请先 task_claim）",
                        System.currentTimeMillis() - startTime);
            }

            List<Task> unlocked = store.unlockedBy(id);
            StringBuilder sb = new StringBuilder();
            sb.append("已完成 #").append(id).append(" ").append(t.getSubject()).append('\n');
            if (!unlocked.isEmpty()) {
                String names = unlocked.stream()
                        .map(u -> "#" + u.getId() + " " + u.getSubject())
                        .collect(Collectors.joining(", "));
                sb.append("解锁下游任务: ").append(names).append('\n');
            }
            sb.append(store.render());
            // 整图完成后清空 .tasks/，为下一轮工作让路（须在 render 之后，先让用户看到全 ✓ 收尾态）
            if (store.allCompleted()) {
                store.clearAll();
                sb.append("\n🧹 全部任务已完成，已清空任务图（.tasks/ 重置）。");
            }
            return success(sb.toString(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("task_complete 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
