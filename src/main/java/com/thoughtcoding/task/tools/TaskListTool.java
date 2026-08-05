package com.thoughtcoding.task.tools;

import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * task_list 工具：列出全部任务，渲染为带图标/阻塞标记的清单。
 * 无参。输出即完整清单（用户终端可见，与旧 todo_write 观感一致）。
 */
public class TaskListTool extends BaseTool {

    private final TaskStore store;

    public TaskListTool(TaskStore store) {
        super("task_list",
                "列出当前全部任务及其状态、owner、依赖。\n"
                + "何时使用：需要查看任务图全貌、确认进度、或决定下一步做哪个任务时。\n"
                + "无参数。返回完整清单，其中 ⛔ 表示 pending 但被依赖阻塞、暂不可开始。");
        this.store = store;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder().additionalProperties(false).build();
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        return success(store.render(), System.currentTimeMillis() - startTime);
    }
}
