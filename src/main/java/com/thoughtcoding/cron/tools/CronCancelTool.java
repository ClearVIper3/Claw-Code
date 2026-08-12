package com.thoughtcoding.cron.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.cron.CronScheduler;
import com.thoughtcoding.cron.CronStore;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * cron_cancel 工具：按 id 取消一个定时任务。durable 任务取消后同步从磁盘移除；
 * 同时清掉该任务已入队但尚未被消费的到点项（否则会「已取消却仍触发」）。
 */
public class CronCancelTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CronStore store;
    private final CronScheduler scheduler;

    public CronCancelTool(CronStore store, CronScheduler scheduler) {
        super("cron_cancel",
                "按 job id 取消一个已登记的定时任务（如 cron_123456）。取消后该任务不再触发。\n"
                + "何时使用：某个定时任务不再需要、要停止循环任务、或要移除误登记的任务时。\n"
                + "用法：job_id（要取消的任务 id，必填，可用 cron_list 查询）。\n"
                + "返回取消结果，并附取消后剩余的任务清单。");
        this.store = store;
        this.scheduler = scheduler;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("job_id", "要取消的定时任务 id（cron_list 可查）")
                .required("job_id")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);
            Object idObj = params.get("job_id");
            if (idObj == null || idObj.toString().isBlank()) {
                return error("cron_cancel 需要非空的 'job_id'", System.currentTimeMillis() - startTime);
            }
            String jobId = idObj.toString().trim();
            if (!store.cancel(jobId)) {
                return error("定时任务 " + jobId + " 不存在（可用 cron_list 查看有效 id）", System.currentTimeMillis() - startTime);
            }
            // 🔥 清掉该任务已入队但尚未被消费的到点项，否则「已取消却仍触发」
            //（消费者会 drain fired 队列；不 purge 时取消前积压的到点任务照样会执行）。
            if (scheduler != null) {
                scheduler.purgeFired(jobId);
            }
            return success("已取消定时任务 " + jobId + "\n" + store.render(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("cron_cancel 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
