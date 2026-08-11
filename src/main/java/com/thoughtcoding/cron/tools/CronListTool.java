package com.thoughtcoding.cron.tools;

import com.thoughtcoding.cron.CronStore;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * cron_list 工具：列出当前全部定时任务（id / cron / prompt / recurring / durable）。
 * 无参。定时任务清单只在模型主动调用本工具时提供（不注入每轮 system-reminder）。
 */
public class CronListTool extends BaseTool {

    private final CronStore store;

    public CronListTool(CronStore store) {
        super("cron_list",
                "列出当前全部已登记的定时任务及其 cron 表达式、prompt、循环/持久属性。\n"
                + "何时使用：查看有哪些定时任务在排期、确认某个 cron 是否已登记、或核对 id 以便取消时。\n"
                + "无参数。返回完整清单。");
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
