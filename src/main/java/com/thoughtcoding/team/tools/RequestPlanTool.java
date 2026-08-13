package com.thoughtcoding.team.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * request_plan 工具（lead 侧）：让一个队友先提交计划再动手（s16 PLAN 审批流程的第一步）。
 *
 * <p>按 s16 语义，这里只发一条<b>普通消息</b>（不登记 pending）——真正的 pending(plan_approval)
 * 由队友调用 {@code submit_plan} 时创建。队友提交后，lead 收件箱会出现一条
 * {@code plan_approval_request}（带 request_id），再用 {@code review_plan} 批准/驳回。
 */
public class RequestPlanTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;

    public RequestPlanTool(ThoughtCodingContext context) {
        super("request_plan",
                "让一个队友先提交一份计划再执行（s16 PLAN 审批）。\n"
                + "队友读到后会调用 submit_plan 提交计划；其计划会作为 plan_approval_request 出现在 lead "
                + "收件箱（带 request_id），随后用 review_plan(request_id, approve, feedback) 批准或驳回；"
                + "队友未获批准前不会动手。\n"
                + "参数：teammate（队友名）、task（要它规划的任务）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("teammate", "要它先交计划的队友名（如 reviewer）")
                .addStringProperty("task", "要规划的任务描述")
                .required("teammate", "task")
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
            Object taskObj = params.get("task");
            if (teammateObj == null || teammateObj.toString().isBlank()
                    || taskObj == null || taskObj.toString().isBlank()) {
                return error("request_plan 需要非空的 'teammate' 与 'task'",
                        System.currentTimeMillis() - startTime);
            }
            return success(context.getTeamManager().requestPlan(
                            teammateObj.toString().trim(), taskObj.toString().trim()),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 兜底：异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("request_plan 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
