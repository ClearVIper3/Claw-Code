package com.thoughtcoding.team.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * send_message 工具：通过消息总线给队友发一条消息（追加指令 / 答疑 / 提问）。
 *
 * <p>lead 侧调用此工具（经 ToolDispatcher）；队友侧同名工具由 {@code Teammate} 循环<b>本地拦截</b>
 * 直接投递到总线、不过 dispatcher —— 同一个工具名，两条路径，共享同一个 {@code MessageBus}。
 */
public class SendMessageTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;

    public SendMessageTool(ThoughtCodingContext context) {
        super("send_message",
                "给某个队友发一条消息（追加指令 / 答疑 / 提问）。队友会在下一轮循环读到它并处理。\n"
                + "注意：队友是异步的——消息到达后需要队友跑完当前轮才会处理；已结束的队友不会再读邮箱。\n"
                + "参数：to（接收方队友名，缺省发给主Agent(lead)）、content（消息正文）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("to", "接收方队友名（如 reviewer）；缺省发给主Agent(lead)自身")
                .addStringProperty("content", "消息正文")
                .required("content")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            if (context.getTeamManager() == null) {
                return error("团队系统未启用（config.yaml 中 team.enabled=false）",
                        System.currentTimeMillis() - startTime);
            }
            Map<String, Object> params = MAPPER.readValue(input, Map.class);
            Object contentObj = params.get("content");
            if (contentObj == null || contentObj.toString().isBlank()) {
                return error("send_message 需要非空的 'content'", System.currentTimeMillis() - startTime);
            }
            String content = contentObj.toString().trim();
            String to = params.get("to") == null ? null : params.get("to").toString().trim();

            context.getTeamManager().sendFromLead(to == null || to.isBlank()
                    ? com.thoughtcoding.team.MessageBus.LEAD : to, content);
            return success("已投递给 " + (to == null || to.isBlank()
                    ? com.thoughtcoding.team.MessageBus.LEAD : to), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 兜底：ToolDispatcher.dispatch 不做 try/catch，异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("send_message 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
