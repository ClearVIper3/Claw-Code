package com.thoughtcoding.team.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * review_plan 工具（lead 侧）：批准/驳回队友提交的计划（s16 PLAN 审批流程的第二步）。
 *
 * <p>request_id 来自 lead 收件箱里的 {@code plan_approval_request} 消息（<team_inbox>/check_inbox
 * 都会展示该 id）。批准后队友收到 {@code plan_approval_response(approve=true)} 开始执行；
 * 驳回则队友按 feedback 修订后重新 submit_plan。终态防重：已审批的请求再次审批会被忽略。
 */
public class ReviewPlanTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;

    public ReviewPlanTool(ThoughtCodingContext context) {
        super("review_plan",
                "批准或驳回队友提交的计划（s16 PLAN 审批）。\n"
                + "request_id 取自已出现在 lead 收件箱的 plan_approval_request 消息（check_inbox 会显示 "
                + "request_id）；approve=true 放行（队友收到后开始执行），approve=false 驳回（可附 feedback，"
                + "队友按反馈修订后重新 submit_plan）。同一 request_id 只能审批一次。\n"
                + "参数：request_id（必填）、approve（布尔，必填）、feedback（驳回意见，可选）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("request_id", "待审批的协议请求 id（来自收件箱的 plan_approval_request）")
                .addBooleanProperty("approve", "true=批准，false=驳回")
                .addStringProperty("feedback", "驳回时给队友的修订意见（可选）")
                .required("request_id", "approve")
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
            Object requestIdObj = params.get("request_id");
            if (requestIdObj == null || requestIdObj.toString().isBlank()) {
                return error("review_plan 需要非空的 'request_id'", System.currentTimeMillis() - startTime);
            }
            boolean approve = Boolean.TRUE.equals(params.get("approve"));
            String feedback = params.get("feedback") == null
                    ? "" : params.get("feedback").toString().trim();
            return success(context.getTeamManager().reviewPlan(
                            requestIdObj.toString().trim(), approve, feedback),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 兜底：异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("review_plan 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
