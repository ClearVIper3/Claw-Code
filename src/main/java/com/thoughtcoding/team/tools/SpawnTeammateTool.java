package com.thoughtcoding.team.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * spawn_teammate 工具：把任务派发给一个【后台队友】异步执行（Agent Teams）。
 *
 * <p>与已移除的 subAgent 的区别 —— 队友是<b>异步、后台、持久</b>的协作者：
 * <ul>
 *   <li>调用后立即返回，主对话不阻塞；队友在自己的隔离循环里干活；</li>
 *   <li>队友通过消息总线向 lead 汇报：交互模式下，队友消息到达后会自动把 lead 唤醒
 *       （一条合并的 &lt;team_inbox&gt; 消息），也可用 check_inbox 主动拉取；</li>
 *   <li>队友看不到主对话历史 —— {@code prompt} 必须自包含（背景/目标/约束/期望结论）；</li>
 *   <li>队友最多跑 maxRounds 轮后结束；给已结束的队友再发消息 = 落入邮箱但不再被读取。</li>
 * </ul>
 *
 * <p>队友不可见 spawn_teammate / check_inbox 工具（从可见工具规格中过滤），不会递归派生。
 */
public class SpawnTeammateTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;

    public SpawnTeammateTool(ThoughtCodingContext context) {
        super("spawn_teammate",
                "把一个可独立完成的任务派发给一个【后台队友】异步执行（Agent Teams）。\n"
                + "何时使用：需要一个后台并行、按需汇报的协作者（如“派个 reviewer 去审某文件并汇报”“并行调研几个独立区域”）；"
                + "队友异步执行——调用后立即返回，你继续干活，队友的汇报稍后作为一条 <team_inbox> 消息把你唤醒。\n"
                + "注意：队友【看不到】当前对话历史，prompt 必须自包含（写清背景、目标、约束、期望的结论形式）；"
                + "队友通过 send_message 汇报；用 check_inbox 主动收信。\n"
                + "参数：name（简短唯一标识）、role（角色描述）、prompt（交给队友的详细任务指令）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("name", "队友的唯一标识（如 reviewer、explorer）")
                .addStringProperty("role", "队友的角色描述（如 代码审查、代码库调研）")
                .addStringProperty("prompt",
                        "交给队友的详细任务指令。必须自包含——队友看不到主对话，"
                        + "请把背景、目标、约束、期望的结论形式都写清楚。")
                .required("name", "role", "prompt")
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
            String name = strParam(params.get("name"));
            String role = strParam(params.get("role"));
            String prompt = strParam(params.get("prompt"));

            if (name == null || name.isBlank()) {
                return error("spawn_teammate 需要非空的 'name'", System.currentTimeMillis() - startTime);
            }
            if (role == null || role.isBlank()) {
                return error("spawn_teammate 需要非空的 'role'", System.currentTimeMillis() - startTime);
            }
            if (prompt == null || prompt.isBlank()) {
                return error("spawn_teammate 需要非空的 'prompt'（队友看不到主对话，任务指令必须自包含）",
                        System.currentTimeMillis() - startTime);
            }

            String receipt = context.getTeamManager().spawn(name.trim(), role.trim(), prompt.trim());
            return success(receipt, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 兜底：ToolDispatcher.dispatch 不做 try/catch，异常绝不能从这里逃逸破坏主轮次的工具配对
            return error("spawn_teammate 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private static String strParam(Object v) {
        return v == null ? null : v.toString();
    }
}
