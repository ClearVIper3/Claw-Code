package com.thoughtcoding.hook;

/**
 * Hook 动作。给定时机触发时执行，返回 {@link HookResult} 决定是否放行/阻断/续跑。
 *
 * <p>约定：动作应尽量轻量、避免抛异常；如需阻断请返回 {@link HookResult#block}。
 * 未处理异常由注册表按 {@link #failurePolicy()} 决定放行或阻断。
 * 应用级 Hook 实例会被多个 Agent 动作链共享，带可变状态的实现必须保证线程安全。
 */
@FunctionalInterface
public interface Hook {

    HookResult execute(HookContext context) throws Exception;

    /** 展示名，用于日志/调试；默认取实现类简单名。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Hook 异常时的处理策略。普通扩展默认 fail-open；权限类 Hook 应覆盖为 fail-closed。
     */
    default HookFailurePolicy failurePolicy() {
        return HookFailurePolicy.FAIL_OPEN;
    }
}
