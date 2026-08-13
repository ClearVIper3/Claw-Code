package com.thoughtcoding.team.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * submit_plan 工具（队友专用）：把计划提交给 lead 审批（s16 PLAN 审批流程）。
 *
 * <p><b>双路模式</b>（对齐 send_message）：全局注册进队友可见的工具规格（不在
 * {@code TEAMMATE_EXCLUDED_TOOLS}），队友调用时由 {@code Teammate} 循环<b>本地拦截</b>
 * （sender 身份 = 队友自身），经 {@code TeamManager.submitPlan} 登记 pending 并向 lead 投递
 * {@code plan_approval_request}；dispatcher 路径（只有 lead 能走到）仅返回提示，不产生任何状态变更。
 *
 * <p>队友提交后应<b>停下等待</b>；收到 {@code plan_approval_response}（批准/驳回）前不要继续执行。
 */
public class SubmitPlanTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SubmitPlanTool(ThoughtCodingContext context) {
        super("submit_plan",
                "【队友专用】把计划提交给 lead 审批。提交后停下等待——收到 plan_approval_response "
                + "（[计划已批准]/[计划被驳回]）前不要继续执行；被驳回则按反馈修订后重新提交。\n"
                + "参数：plan（计划内容）。");
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("plan", "要提交给 lead 审批的计划内容")
                .required("plan")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        // lead 侧走到这里：submit_plan 仅供队友本地拦截使用，lead 无需调用（也不应产生状态变更）
        return error("submit_plan 仅供后台队友使用（由队友本地拦截路由）。lead 无需调用。",
                System.currentTimeMillis() - startTime);
    }
}
