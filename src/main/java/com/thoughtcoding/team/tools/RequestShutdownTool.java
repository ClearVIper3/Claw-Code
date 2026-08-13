package com.thoughtcoding.team.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * request_shutdown 工具（lead 侧）：发起 s16 SHUTDOWN 握手 —— 请求一个队友优雅关闭。
 *
 * <p>登记 pending(shutdown) 并向队友投递 {@code shutdown_request}（带 request_id）；队友读到后
 * 自动回 {@code shutdown_response(approve=true)} 并退出，lead 经收件箱路由 {@code matchResponse}
 * 把状态流转为 approved。队友侧不可见此工具（已在 {@code TEAMMATE_EXCLUDED_TOOLS} 中过滤）。
 */
public class RequestShutdownTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;

    public RequestShutdownTool(ThoughtCodingContext context) {
        super("request_shutdown",
                "请求一个队友优雅关闭（s16 SHUTDOWN 握手）。\n"
                + "队友会在下一轮循环读到 shutdown_request，自动确认（shutdown_response）后优雅退出并汇报；"
                + "lead 收件箱会出现其确认与最终结果。\n"
                + "参数：teammate（队友名）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("teammate", "要关闭的队友名（如 reviewer）")
                .required("teammate")
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
            Object teammateObj = params.get("teammate");
            if (teammateObj == null || teammateObj.toString().isBlank()) {
                return error("request_shutdown 需要非空的 'teammate'", System.currentTimeMillis() - startTime);
            }
            return success(context.getTeamManager().requestShutdown(teammateObj.toString().trim()),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 兜底：异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("request_shutdown 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
