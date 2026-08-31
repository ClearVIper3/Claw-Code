package com.thoughtcoding.hook;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 注册表 —— Hook 系统的统一管理者，仿照 {@code ToolRegistry} 的注册表理念。
 *
 * <p>构造时即为 {@link HookType} 的四种时机各建立一条有序动作链；
 * {@link #register} 按顺序追加动作，{@link #fire} 在对应时机 <b>串行</b> 触发。
 * 动作链使用写时复制列表，允许并行 Agent 安全读取，并支持在 Runtime 启动阶段注册扩展。
 *
 * <p>串行语义：
 * <ul>
 *   <li>任一动作返回 {@link HookResult.Decision#BLOCK} → 立即终止本链，返回该阻断结果；</li>
 *   <li>STOP 时机任一动作返回 {@link HookResult.Decision#CONTINUE_LOOP} → 记为「强制续跑」并终止本链；</li>
 *   <li>动作抛出异常 → 按动作声明的 {@link HookFailurePolicy} 放行或阻断。</li>
 * </ul>
 */
public class HookRegistry {

    /** 动作及其展示名的绑定。 */
    private record Entry(String name, Hook hook, HookFailurePolicy failurePolicy) {}

    private final Map<HookType, List<Entry>> hooks = new EnumMap<>(HookType.class);

    public HookRegistry() {
        // 一开始先注册（建立）四种 hook 时机
        for (HookType type : HookType.values()) {
            hooks.put(type, new CopyOnWriteArrayList<>());
        }
    }

    /** 向指定时机追加一个动作，返回自身以便链式注册。 */
    public HookRegistry register(HookType type, String name, Hook hook) {
        return register(type, name, hook != null ? hook.failurePolicy() : null, hook);
    }

    /** 向指定时机追加一个动作，并显式指定其异常处理策略。 */
    public HookRegistry register(HookType type, String name,
                                 HookFailurePolicy failurePolicy, Hook hook) {
        if (type == null || hook == null) return this;
        HookFailurePolicy policy = failurePolicy != null
                ? failurePolicy : HookFailurePolicy.FAIL_OPEN;
        hooks.get(type).add(new Entry(name != null ? name : hook.name(), hook, policy));
        return this;
    }

    public HookRegistry register(HookType type, Hook hook) {
        return register(type, hook != null ? hook.name() : null, hook);
    }

    /**
     * 将动作插入指定时机的最前面。安全/权限 Hook 使用此前置入口，确保先于业务扩展执行。
     */
    public HookRegistry registerFirst(HookType type, String name, Hook hook) {
        if (type == null || hook == null) return this;
        HookFailurePolicy declaredPolicy = hook.failurePolicy();
        HookFailurePolicy policy = declaredPolicy != null
                ? declaredPolicy : HookFailurePolicy.FAIL_OPEN;
        hooks.get(type).add(0, new Entry(name != null ? name : hook.name(), hook, policy));
        return this;
    }

    public HookRegistry registerFirst(HookType type, Hook hook) {
        return registerFirst(type, hook != null ? hook.name() : null, hook);
    }

    /**
     * 派生独立动作链快照。列表彼此隔离，Hook 实例本身共享，便于审计/指标 Hook 聚合状态。
     */
    public HookRegistry copy() {
        HookRegistry copy = new HookRegistry();
        for (HookType type : HookType.values()) {
            copy.hooks.get(type).addAll(hooks.get(type));
        }
        return copy;
    }

    /**
     * 在指定时机串行触发所有动作。
     *
     * @return 聚合结果：命中 BLOCK 则返回该阻断；STOP 命中续跑则返回 CONTINUE_LOOP；否则 PROCEED。
     */
    public HookResult fire(HookContext context) {
        List<Entry> chain = hooks.getOrDefault(context.getType(), Collections.emptyList());
        if (chain.isEmpty()) {
            return HookResult.proceed(); // 空链：不打印、不做任何事
        }

        // 打印本时机注册的动作：Hook(a、b、c)，走项目 UI 而非裸 System.out
        StringBuilder names = new StringBuilder();
        for (Entry entry : chain) {
            if (names.length() > 0) names.append("、");
            names.append(entry.name());
        }
        if (context.getUi() != null) {
            context.getUi().displayInfo(context.getType() + " Hook(" + names + ")");
        }

        for (Entry entry : chain) {
            HookResult result;
            try {
                result = entry.hook().execute(context);
            } catch (Exception e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String message = "Hook 执行异常 [" + context.getType() + "/" + entry.name()
                        + "]: " + detail;
                if (entry.failurePolicy() == HookFailurePolicy.FAIL_CLOSED) {
                    return HookResult.block(message + "；已按安全策略阻断当前操作。");
                }
                System.err.println("⚠️ " + message + "；已降级放行。");
                continue;
            }
            if (result == null) continue;

            if (result.isBlocked() || result.isContinueLoop()) {
                return result; // 阻断 / 强制续跑 → 提前终止串行链
            }
        }
        return HookResult.proceed();
    }

    /** 某时机已注册的动作数量（便于调试/测试）。 */
    public int count(HookType type) {
        return hooks.getOrDefault(type, Collections.emptyList()).size();
    }
}
