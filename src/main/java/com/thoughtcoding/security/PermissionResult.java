package com.thoughtcoding.security;

/**
 * 权限检查结果。
 *
 * <pre>
 *   deny  → 硬拒绝，不弹确认，直接报错
 *   warn  → 命中规则，强制弹确认（即使只读工具也不例外）
 *   allow → 正常走确认流程（该确认的工具会确认，只读工具静默放行）
 * </pre>
 */
public record PermissionResult(Type type, String message) {

    public enum Type { DENY, WARN, ALLOW }

    public static PermissionResult deny(String message) {
        return new PermissionResult(Type.DENY, message);
    }

    public static PermissionResult warn(String message) {
        return new PermissionResult(Type.WARN, message);
    }

    public static final PermissionResult ALLOW = new PermissionResult(Type.ALLOW, null);
}
