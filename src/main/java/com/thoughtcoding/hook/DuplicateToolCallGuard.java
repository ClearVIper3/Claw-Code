package com.thoughtcoding.hook;

import com.thoughtcoding.model.ToolCall;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 重复工具调用防护 Hook —— Agent 循环死循环兜底。
 *
 * <p>拦截"同一消费方连续两次以完全相同的入参调用同一个工具"中的第二次：
 * 不真正执行工具，直接以报错文本作为 ToolResult 回喂模型（经
 * {@link com.thoughtcoding.core.ToolExecutionPipeline} 的 PreToolUse BLOCK 短路路径），
 * 打破模型原地打转。
 *
 * <p>注册约定：由各 Agent 循环（AgentLoop / SubAgent）在派生的 HookRegistry 上
 * registerFirst 注册，且先于 PermissionHook —— 避免对注定被拦的调用重复弹权限确认框。
 * DirectCommandExecutor 不注册：斜杠命令由用户主动发起，连续重复属合法操作。
 * AgentLoop 须在每轮用户输入（processInput）开始时调用 {@link #reset()}，
 * 避免"上一轮的最后一次调用"误拦"下一轮的首次相同调用"。
 *
 * <p>线程安全：主 Agent 的并行子代理预执行（虚拟线程）会并发触发本 Hook，
 * last 状态由同一把锁保护。已知局限：只防"连续重复"（窗口=1），
 * A,B,A,B 交替循环由 ai.maxToolIterations 兜底。
 *
 * <p>异常策略：默认 FAIL_OPEN —— 比较逻辑异常时降级放行，由 maxToolIterations 最终兜底。
 */
public class DuplicateToolCallGuard implements Hook {

    /** 回喂模型的核心报错文案（测试按此原文断言）。 */
    static final String BLOCK_MESSAGE = "不可重复以相同的入参调用同一个工具";

    private final Object lock = new Object();
    private String lastToolName;
    private Map<String, Object> lastParameters;

    @Override
    public HookResult execute(HookContext context) {
        ToolCall call = context.getToolCall();
        if (call == null || call.getToolName() == null) {
            return HookResult.proceed();
        }
        // 参数 null 归一化为空 Map；Jackson 产出的 LinkedHashMap 与 HashMap 按 entry 集合互比等价
        Map<String, Object> parameters = call.getParameters() != null
                ? call.getParameters() : Map.of();

        synchronized (lock) {
            if (call.getToolName().equals(lastToolName)
                    && Objects.equals(parameters, lastParameters)) {
                return HookResult.block(BLOCK_MESSAGE + " [" + call.getToolName()
                        + "]。上一次相同调用已执行，请查看其结果，调整入参或改用其他方式，勿原样重试。");
            }
            // last = 上次放行的调用。被拦截调用不更新：拦截前提即当前调用与 last 相同，更新与否严格等价。
            // 快照用 HashMap（容忍 null value，Map.copyOf 会抛 NPE）。
            lastToolName = call.getToolName();
            lastParameters = new HashMap<>(parameters);
        }
        return HookResult.proceed();
    }

    /** 清空记忆。每轮用户对话开始时调用，防止跨轮误拦。 */
    public void reset() {
        synchronized (lock) {
            lastToolName = null;
            lastParameters = null;
        }
    }
}
