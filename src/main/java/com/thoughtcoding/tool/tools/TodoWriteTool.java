package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * todo_write 工具：创建并维护一个结构化的待办清单，用于规划、跟踪多步骤任务的进度。
 *
 * <p>它<b>不增加任何执行能力</b>——不读写文件、不跑命令，只是把模型给出的完整任务清单
 * 存在会话内并渲染出来。它增加的是<b>规划能力</b>：让模型显式拆解大任务、跟踪进度、
 * 上下文变长后也不会忘记待办。
 *
 * <p>状态保存在实例字段里：本工具在 {@code ToolRegistry} 中是单例，同一会话内多次调用
 * 复用同一实例，因此 {@link #todos} 跨调用保留（等价于教学示例里的全局 {@code CURRENT_TODOS}）。
 * {@code AgentLoop} 单线程顺序执行工具，无并发问题。模型每次都传【完整】列表，整体替换。
 */
public class TodoWriteTool extends BaseTool {

    /** 合法状态值。 */
    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 会话内的当前任务清单（跨调用保留）。 */
    private final List<Todo> todos = new ArrayList<>();

    /** 单个待办项：任务描述 + 状态。 */
    private record Todo(String content, String status) {
    }

    public TodoWriteTool() {
        super("todo_write",
                "创建并维护一个结构化的待办清单，用于规划和跟踪多步骤任务的进度。\n"
                + "何时使用：任务需要 3 个及以上步骤、较复杂/非平凡；用户一次给出多项任务；或你刚开始一项较大的任务时。\n"
                + "用法：传入【完整】的 todos 列表（每次调用都要传全量，包含已完成项，而非只传增量）；"
                + "每项含 content（任务描述，祈使句）与 status（pending/in_progress/completed）。\n"
                + "规则：同一时刻最多只应有一个任务处于 in_progress；完成一项后立即标记为 completed，并把下一项置为 in_progress。\n"
                + "注意：本工具只做规划、不执行任何实际操作，也不读写文件。简单的单步任务或纯咨询类问题无需使用。");
    }

    @Override
    public JsonObjectSchema inputSchema() {
        JsonObjectSchema item = JsonObjectSchema.builder()
                .addStringProperty("content", "任务的简短描述（祈使句，如“实现登录接口”）")
                .addEnumProperty("status", STATUSES, "任务状态：pending（待办）/ in_progress（进行中）/ completed（已完成）")
                .required("content", "status")
                .additionalProperties(false)
                .build();

        JsonArraySchema todosArray = JsonArraySchema.builder()
                .description("完整的任务清单。每次调用都要传【全量】列表（含已完成项），而不是增量。")
                .items(item)
                .build();

        return JsonObjectSchema.builder()
                .addProperty("todos", todosArray)
                .required("todos")
                .additionalProperties(false)
                .build();
    }

    /** 纯规划工具，无副作用，静默放行（权限见 PermissionGate 的 todo_write 分支）。 */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);

            Object todosObj = params.get("todos");
            if (!(todosObj instanceof List)) {
                return error("todo_write 需要 'todos' 数组（完整任务清单）", System.currentTimeMillis() - startTime);
            }

            List<Object> raw = (List<Object>) todosObj;

            // 空列表 → 清空清单
            if (raw.isEmpty()) {
                todos.clear();
                return success("任务清单已清空。", System.currentTimeMillis() - startTime);
            }

            List<Todo> parsed = new ArrayList<>();
            for (Object o : raw) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> item = (Map<String, Object>) o;

                Object contentObj = item.get("content");
                String content = contentObj == null ? "" : contentObj.toString().trim();
                if (content.isEmpty()) {
                    continue; // 跳过无 content 的无效项
                }

                Object statusObj = item.get("status");
                String status = statusObj == null ? "" : statusObj.toString().trim().toLowerCase(Locale.ROOT);
                if (!STATUSES.contains(status)) {
                    status = "pending"; // 非法/缺失状态回退为 pending
                }

                parsed.add(new Todo(content, status));
            }

            if (parsed.isEmpty()) {
                return error("没有有效的任务项（每项需包含非空 content）", System.currentTimeMillis() - startTime);
            }

            // 整体替换会话内清单
            todos.clear();
            todos.addAll(parsed);

            return success(render(), System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return error("todo_write 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /** 把当前清单渲染为带图标的进度视图（既用于终端显示，也回喂给模型确认状态）。 */
    private String render() {
        long done = todos.stream().filter(t -> "completed".equals(t.status())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("TodoList (").append(done).append('/').append(todos.size()).append(")\n");
        for (Todo t : todos) {
            sb.append("  ").append(icon(t.status())).append(' ').append(t.content()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String icon(String status) {
        return switch (status) {
            case "completed" -> "✓";
            case "in_progress" -> "▸";
            default -> "○";
        };
    }
}
