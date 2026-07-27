package com.thoughtcoding.hook;

/**
 * Hook 动作。给定时机触发时执行，返回 {@link HookResult} 决定是否放行/阻断/续跑。
 *
 * <p>约定：动作应尽量轻量、避免抛异常；如需阻断请返回 {@link HookResult#block}，
 * 注册表会捕获未受检异常并降级为放行（不因单个 hook 崩溃而中断主循环）。
 */
@FunctionalInterface
public interface Hook {

    HookResult execute(HookContext context) throws Exception;

    /** 展示名，用于日志/调试；默认取实现类简单名。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
