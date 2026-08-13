package com.thoughtcoding.worktree.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.worktree.GitWorktreeManager;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.nio.file.Files;
import java.util.Map;

/**
 * create_worktree 工具（Lead 侧，s18）：在 {@code <baseDir>/<name>} 建一个带独立分支
 * {@code wt/<name>} 的 git worktree，可选地绑定到某个任务（{@code task_id}）——绑定后认领该任务的
 * 队友会在该 worktree 副本内执行工具，与其他队友隔离。
 */
public class CreateWorktreeTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitWorktreeManager manager;
    private final TaskStore taskStore;

    public CreateWorktreeTool(GitWorktreeManager manager, TaskStore taskStore) {
        super("create_worktree",
                "创建一个带独立分支的隔离 git worktree（" + "目录 <baseDir>/<name>，分支 wt/<name>）。\n"
                + "何时使用：想让某个队友在与仓库根隔离的副本里干活时，先建 worktree 再绑定任务。\n"
                + "用法：name（worktree 名，必填，仅字母/数字/点/下划线/短横线）、"
                + "task_id（可选，绑定到该任务——认领它的队友工具 cwd 会切到此 worktree）。");
        this.manager = manager;
        this.taskStore = taskStore;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("name", "worktree 名（仅字母/数字/点/下划线/短横线，1-64 字符）")
                .addStringProperty("task_id", "要绑定的任务 id（可选；绑定后认领该任务的队友在此 worktree 内工作）")
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
                return error("create_worktree 需要 'name'", System.currentTimeMillis() - startTime);
            }
            String name = nameObj.toString().trim();

            // 归一化：模型常照抄工具输出里的 "#1" 形式，落盘 id 是纯数字（store 内部也会再归一一次）
            Object taskIdObj = params.get("task_id");
            String taskId = null;
            if (taskIdObj != null && !taskIdObj.toString().isBlank()) {
                taskId = taskIdObj.toString().trim();
                if (taskId.startsWith("#")) {
                    taskId = taskId.substring(1).trim();
                }
            }

            GitWorktreeManager.GitResult r = manager.createWorktree(name);
            boolean reused = false;
            if (!r.ok()) {
                // 幂等：仅"目录已存在"且要带 task_id 绑定时，降级为复用现存 worktree（重跑/上次残留的
                // 场景）；其它失败（git 错误、非法名、或未带 task_id）照常报错。
                if (taskId != null && Files.exists(manager.worktreeDir(name))) {
                    reused = true;
                } else {
                    return error(r.output(), System.currentTimeMillis() - startTime);
                }
            }

            StringBuilder msg = new StringBuilder(reused
                    ? "worktree '" + name + "' 已存在，复用它（未重建）。"
                    : r.output());
            if (taskId != null && taskStore != null) {
                var bound = taskStore.bindWorktree(taskId, name);
                if (bound == null) {
                    msg.append("\n⚠️ 绑定失败：任务 #").append(taskId).append(" 不存在");
                } else {
                    msg.append("\n已绑定任务 #").append(taskId).append("（")
                            .append(bound.getSubject()).append("）→ worktree ").append(name);
                }
            }
            return success(msg.toString(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("create_worktree 参数解析失败: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }
}
