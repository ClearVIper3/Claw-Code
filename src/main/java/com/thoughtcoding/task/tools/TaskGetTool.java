package com.thoughtcoding.task.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.task.Task;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * task_get 工具：返回单个任务的完整 JSON 明细（含 description/owner/blockedBy）。
 * 明细体积小、主要给模型确认状态用，故已加入 AgentLoop.QUIET_OUTPUT_TOOLS（不在用户端刷屏）。
 */
public class TaskGetTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskStore store;

    public TaskGetTool(TaskStore store) {
        super("task_get",
                "获取单个任务的完整详情（JSON 格式，含 description、owner、blockedBy 依赖）。\n"
                + "何时使用：需要查看某任务的完整信息（list 只给摘要行）时。\n"
                + "用法：id（任务 id，必填）。");
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
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            String id = MAPPER.readValue(input, Map.class).get("id").toString();
            Task t = store.get(id);
            if (t == null) {
                return error("任务 #" + id + " 不存在", System.currentTimeMillis() - startTime);
            }
            return success(MAPPER.writeValueAsString(t), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("task_get 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
