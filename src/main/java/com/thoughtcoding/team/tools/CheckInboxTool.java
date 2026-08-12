package com.thoughtcoding.team.tools;

import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.team.MessageBus;
import com.thoughtcoding.team.TeamMessage;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;

/**
 * check_inbox 工具：主动读取 lead 收件箱中的队友消息（pull 路径）。
 *
 * <p>交互模式下队友消息会自动把 lead 唤醒（合并成一条 &lt;team_inbox&gt;），但单次
 * {@code --prompt} 模式没有唤醒消费者线程，此工具是唯一主动收信的方式。
 */
public class CheckInboxTool extends BaseTool {

    private final ThoughtCodingContext context;

    public CheckInboxTool(ThoughtCodingContext context) {
        super("check_inbox",
                "主动检查收件箱：读取队友发来的消息（读即销毁，读出后清空）。\n"
                + "何时使用：想主动看队友有没有汇报；交互模式下队友消息会自动把你唤醒，此工具用于主动拉取。\n"
                + "无参数。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder().additionalProperties(false).build();
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            if (context.getTeamManager() == null) {
                return error("团队系统未启用（config.yaml 中 team.enabled=false）",
                        System.currentTimeMillis() - startTime);
            }
            List<TeamMessage> msgs = context.getTeamManager().drainLeadInbox();
            if (msgs == null || msgs.isEmpty()) {
                return success("收件箱为空。", System.currentTimeMillis() - startTime);
            }
            StringBuilder sb = new StringBuilder("收件箱（" + msgs.size() + " 条消息）：\n");
            for (TeamMessage m : msgs) {
                String from = m.getFrom() == null ? "" : m.getFrom();
                String type = m.getType() == null ? "message" : m.getType();
                String content = m.getContent() == null ? "" : m.getContent();
                sb.append("  [").append(type).append("] 来自 ").append(from).append("：")
                        .append(content).append("\n");
            }
            return success(sb.toString().stripTrailing(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 兜底：ToolDispatcher.dispatch 不做 try/catch，异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("check_inbox 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
