package com.thoughtcoding.task.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.task.Task;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

/**
 * task_create 工具：创建任务并分配稳定短序列 id，可选带 blockedBy 依赖边。
 * 创建成功后向用户渲染完整清单（UX 与旧 todo_write 一致）。
 */
public class TaskCreateTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskStore store;

    public TaskCreateTool(TaskStore store) {
        super("task_create",
                "创建一个新任务并分配稳定 id。任务图支持 blockedBy 依赖：只有依赖全部完成后任务才能开始。\n"
                + "何时使用：规划多步骤任务时，每步建一个任务，可显式声明前置依赖。\n"
                + "用法：subject（任务描述，祈使句，必填）、description（可选详情）、"
                + "blockedBy（依赖任务 id 数组，可选；这些任务 completed 前本任务会被标记为阻塞）。\n"
                + "创建成功后返回当前完整任务清单。");
        this.store = store;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        JsonArraySchema blockedByArray = JsonArraySchema.builder()
                .description("依赖任务 id 数组（可选）。这些任务 completed 前，本任务视为阻塞，不可 claim。")
                .items(JsonStringSchema.builder().build())
                .build();
        return JsonObjectSchema.builder()
                .addStringProperty("subject", "任务描述（祈使句，如“实现登录接口”）")
                .addStringProperty("description", "任务详情（可选）")
                .addProperty("blockedBy", blockedByArray)
                .required("subject")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);
            Object subjectObj = params.get("subject");
            if (subjectObj == null || subjectObj.toString().isBlank()) {
                return error("task_create 需要非空的 'subject'", System.currentTimeMillis() - startTime);
            }
            String subject = subjectObj.toString().trim();
            String description = params.get("description") == null ? null : params.get("description").toString();
            List<String> blockedBy = stringList(params.get("blockedBy"));

            Task t = store.create(subject, description, blockedBy);
            String deps = blockedBy.isEmpty() ? "" : " (blockedBy: " + String.join(", ", blockedBy) + ")";
            return success("已创建 #" + t.getId() + " " + t.getSubject() + deps + "\n" + store.render(),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("task_create 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /** 把参数值容错转成 String 列表（JSON 数组元素可能是 Number，统一 String.valueOf）。 */
    static List<String> stringList(Object raw) {
        if (!(raw instanceof List)) {
            return List.of();
        }
        return ((List<?>) raw).stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
