package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.SubAgent;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * task 工具：把一个复杂、可独立完成的多步子任务派发给【子代理】处理。
 *
 * <p>子代理在全新、隔离的对话历史里跑自己的 agentic 循环（可用除 task 外的全部工具），
 * 跑完只把<b>最终结论</b>回传给主代理，所有中间过程（读文件、跑命令、试错）都不进主上下文。
 * 因此它增加的是「把大任务外包出去、保持主上下文干净」的能力，而非新的执行能力。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>子代理看不到主对话历史 —— {@code prompt} 必须自包含，把背景/目标/约束/验收标准讲清楚；</li>
 *   <li>子代理<b>不能</b>再派发子代理（本工具用 {@link #RUNNING} ThreadLocal 结构性阻断嵌套，
 *       与 {@link SubAgent} 循环里的按名拦截构成双保险）；</li>
 *   <li>只返回最终结论，无法拿回中间步骤；</li>
 *   <li>子代理内部的写/执行类工具照样走权限确认（安全不打折）。</li>
 * </ul>
 */
public class SubAgentTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 结构性防递归：标记当前线程是否已在子代理内运行。 */
    private static final ThreadLocal<Boolean> RUNNING = ThreadLocal.withInitial(() -> false);

    private final ThoughtCodingContext context;

    public SubAgentTool(ThoughtCodingContext context) {
        super("subAgent",
                "把一个复杂、可独立完成的多步子任务派发给子代理处理，让主对话保持干净。\n"
                + "何时使用：任务较大且可独立完成（如“在某模块实现并自测一个功能”“调研代码库某问题并给出结论”），"
                + "其大量中间步骤（读文件、跑命令、试错）不需要留在主对话里时。简单/单步任务直接自己做，无需使用。\n"
                + "注意：子代理【看不到】当前对话历史，prompt 必须自包含（写清背景、目标、约束、期望的结论形式）；"
                + "子代理只会回传【最终结论】，中间过程不保留；子代理【不能】再派发子代理。\n"
                + "参数：description（简短任务标签，用于展示）、prompt（交给子代理的详细任务指令）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("description", "子代理任务的简短标签（3-8 字，用于终端展示）")
                .addStringProperty("prompt",
                        "交给子代理的详细任务指令。必须自包含——子代理看不到主对话，"
                        + "请把背景、目标、约束、期望的结论形式都写清楚。")
                .required("description", "prompt")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            // 结构性防递归：子代理内部即使幻觉调用 task，也在这里被挡下
            if (Boolean.TRUE.equals(RUNNING.get())) {
                return error("不支持嵌套子代理：task 不能在子代理内部再次调用。",
                        System.currentTimeMillis() - startTime);
            }

            Map<String, Object> params = MAPPER.readValue(input, Map.class);

            Object promptObj = params.get("prompt");
            String prompt = promptObj == null ? "" : promptObj.toString().trim();
            if (prompt.isEmpty()) {
                return error("task 需要 'prompt' 字段（交给子代理的详细任务指令）",
                        System.currentTimeMillis() - startTime);
            }

            Object descObj = params.get("description");
            String description = descObj == null ? "" : descObj.toString().trim();
            if (description.isEmpty()) {
                return error("task 需要 'description' 字段（简短任务标签）",
                        System.currentTimeMillis() - startTime);
            }

            RUNNING.set(true);
            try {
                String conclusion = new SubAgent(context).run(prompt, description);
                return success(
                        conclusion == null || conclusion.isBlank()
                                ? "子代理已结束，但未产出文本结论。" : conclusion,
                        System.currentTimeMillis() - startTime);
            } finally {
                RUNNING.set(false);
            }
        } catch (Exception e) {
            // 兜底：ToolDispatcher.dispatch 不做 try/catch，异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("task 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
