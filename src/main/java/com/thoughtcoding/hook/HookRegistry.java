package com.thoughtcoding.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 注册表 —— Hook 系统的统一管理者，仿照 {@code ToolRegistry} 的注册表理念。
 *
 * <p>构造时即为 {@link HookType} 的四种时机各建立一条有序动作链；
 * {@link #register} 按顺序追加动作，{@link #fire} 在对应时机 <b>串行</b> 触发。
 *
 * <p>串行语义：
 * <ul>
 *   <li>任一动作返回 {@link HookResult.Decision#BLOCK} → 立即终止本链，返回该阻断结果；</li>
 *   <li>STOP 时机任一动作返回 {@link HookResult.Decision#CONTINUE_LOOP} → 记为「强制续跑」并终止本链；</li>
 *   <li>动作抛出异常 → 捕获并降级为放行，不中断主循环。</li>
 * </ul>
 */
public class HookRegistry {

    /** 动作及其展示名的绑定。 */
    private record Entry(String name, Hook hook) {}

    private final Map<HookType, List<Entry>> hooks = new EnumMap<>(HookType.class);

    public HookRegistry() {
        // 一开始先注册（建立）四种 hook 时机
        for (HookType type : HookType.values()) {
            hooks.put(type, new ArrayList<>());
        }
    }

    /** 向指定时机追加一个动作，返回自身以便链式注册。 */
    public HookRegistry register(HookType type, String name, Hook hook) {
        if (type == null || hook == null) return this;
        hooks.get(type).add(new Entry(name != null ? name : hook.name(), hook));
        return this;
    }

    public HookRegistry register(HookType type, Hook hook) {
        return register(type, hook.name(), hook);
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
                // 单个 hook 崩溃不应中断主循环 —— 降级为放行
                System.err.println("⚠️ Hook 执行异常 [" + context.getType() + "/" + entry.name()
                        + "]: " + e.getMessage());
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
