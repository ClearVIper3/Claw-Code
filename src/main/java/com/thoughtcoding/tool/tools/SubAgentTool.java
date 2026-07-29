package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.SubAgent;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * subAgent 工具：把一个复杂、可独立完成的多步子任务派发给【子Agent】处理。
 *
 * <p>子Agent在全新、隔离的对话历史里跑自己的 agentic 循环（可用除 subAgent 外的全部工具），
 * 跑完只把<b>最终结论</b>回传给主Agent，所有中间过程（读文件、跑命令、试错）都不进主上下文。
 * 因此它增加的是「把大任务外包出去、保持主上下文干净」的能力，而非新的执行能力。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>子Agent看不到主对话历史 —— {@code prompt} 必须自包含，把背景/目标/约束/验收标准讲清楚；</li>
 *   <li>只返回最终结论，无法拿回中间步骤；</li>
 *   <li>子Agent内部的写/执行类工具照样走权限确认（安全不打折）；</li>
 *   <li>不会递归：{@code chatOnceForSubagent} 已把 subAgent 自身从子Agent可见的工具规格中过滤掉，
 *       模型根本拿不到这个工具，也就无从再派发子Agent。</li>
 * </ul>
 */
public class SubAgentTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;

    public SubAgentTool(ThoughtCodingContext context) {
        super("subAgent",
                "把一个复杂、可独立完成的多步子任务派发给子Agent处理，让主对话保持干净。\n"
                + "何时使用：任务较大且可独立完成（如“在某模块实现并自测一个功能”“调研代码库某问题并给出结论”），"
                + "其大量中间步骤（读文件、跑命令、试错）不需要留在主对话里时。简单/单步任务直接自己做，无需使用。\n"
                + "注意：子Agent【看不到】当前对话历史，prompt 必须自包含（写清背景、目标、约束、期望的结论形式）；"
                + "子Agent只会回传【最终结论】，中间过程不保留。\n"
                + "参数：description（简短任务标签，用于展示）、prompt（交给子Agent的详细任务指令）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("description", "子Agent任务的简短标签（3-8 字，用于终端展示）")
                .addStringProperty("prompt",
                        "交给子Agent的详细任务指令。必须自包含——子Agent看不到主对话，"
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
            Map<String, Object> params = MAPPER.readValue(input, Map.class);

            Object promptObj = params.get("prompt");
            String prompt = promptObj == null ? "" : promptObj.toString().trim();
            if (prompt.isEmpty()) {
                return error("subAgent 需要 'prompt' 字段（交给子Agent的详细任务指令）",
                        System.currentTimeMillis() - startTime);
            }

            Object descObj = params.get("description");
            String description = descObj == null ? "" : descObj.toString().trim();
            if (description.isEmpty()) {
                return error("subAgent 需要 'description' 字段（简短任务标签）",
                        System.currentTimeMillis() - startTime);
            }

            String conclusion = new SubAgent(context).run(prompt, description);
            return success(
                    conclusion == null || conclusion.isBlank()
                            ? "子Agent已结束，但未产出文本结论。" : conclusion,
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 兜底：ToolDispatcher.dispatch 不做 try/catch，异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("subAgent 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
