package com.thoughtcoding.hook;

/**
 * Hook 执行异常时的处理策略。
 */
public enum HookFailurePolicy {
    /** Hook 异常时记录告警并继续执行，适用于日志、观测、上下文增强等非安全能力。 */
    FAIL_OPEN,
    /** Hook 异常时阻断当前操作，适用于权限、安全等不能绕过的能力。 */
    FAIL_CLOSED
}
