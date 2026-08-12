package com.thoughtcoding.cron.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.cron.CronStore;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * cron_schedule 工具：登记一个定时任务。
 * cron 为 5 段表达式（minute hour day-of-month month day-of-week），prompt 为到点注入给 Agent 的提示。
 * recurring 是否循环（默认 true）；durable 是否落盘跨会话恢复（默认 true）。
 */
public class CronScheduleTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CronStore store;

    public CronScheduleTool(CronStore store) {
        super("cron_schedule",
                "登记一个定时任务：到点后自动把 prompt 作为一条用户消息注入给 Agent 执行（无需你输入）。\n"
                + "cron 为 5 段表达式：minute(0-59) hour(0-23) day-of-month(1-31) month(1-12) day-of-week(0-6,0=周日)；"
                + "支持 *、*/step、a-b、a,b,c。\n"
                + "何时使用：需要定时/周期性地让 Agent 自动做某事（如每早 9 点总结、每小时检查构建）。\n"
                + "用法：cron（表达式，必填）、prompt（到点执行的提示，必填）、"
                + "recurring（是否循环，默认 true）、durable（是否跨会话持久，默认 true；one-shot 触发一次后自动移除）。\n"
                + "创建成功后返回新任务的 id。");
        this.store = store;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("cron", "5 段 cron 表达式（minute hour day-of-month month day-of-week）")
                .addStringProperty("prompt", "到点后注入给 Agent 执行的提示文本")
                .addBooleanProperty("recurring", "是否循环执行（默认 true；false=只触发一次）")
                .addBooleanProperty("durable", "是否落盘跨会话恢复（默认 true；false=仅当前进程有效）")
                .required("cron", "prompt")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);
            Object cronObj = params.get("cron");
            Object promptObj = params.get("prompt");
            if (cronObj == null || cronObj.toString().isBlank()) {
                return error("cron_schedule 需要非空的 'cron'", System.currentTimeMillis() - startTime);
            }
            if (promptObj == null || promptObj.toString().isBlank()) {
                return error("cron_schedule 需要非空的 'prompt'", System.currentTimeMillis() - startTime);
            }
            String cron = cronObj.toString().trim();
            String prompt = promptObj.toString().trim();
            boolean recurring = boolParam(params, "recurring", true);
            boolean durable = boolParam(params, "durable", true);

            String id = store.schedule(cron, prompt, recurring, durable);
            // schedule 在 cron 校验失败时返回错误串（合法 id 恒为 "cron_xxxxxx"）
            if (id == null || !id.startsWith("cron_")) {
                return error(id == null ? "cron_schedule 登记失败" : id, System.currentTimeMillis() - startTime);
            }
            return success("已登记定时任务 #" + id + ": '" + cron + "' → " + prompt
                    + " [" + (recurring ? "recurring" : "one-shot") + ", " + (durable ? "durable" : "session") + "]\n"
                    + store.render(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("cron_schedule 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /** 解析布尔参数：缺省用默认值；容错接受 true/false 字符串。 */
    private static boolean boolParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object v = params.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(v.toString());
    }
}
