package com.thoughtcoding.security;

import com.thoughtcoding.exception.WorkspaceSecurityException;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 通用沙箱 —— 路径解析工具。
 *
 * <pre>
 * // 工具内用法（只解析，不做权限决策）：
 * Path path = Sandbox.resolve(p.toString());
 *
 * // PermissionGate 内用法（组合判断）：
 * Path resolved = Sandbox.resolve(rawPath);
 * if (!Sandbox.isWithinWorkspace(resolved)) {
 *     return PermissionResult.warn("⚠️ 路径在 workspace 之外");
 * }
 * </pre>
 *
 * 权限决策由 PermissionGate 负责，AgentLoop 通过确认框交给用户选择，
 * 默认 ask 而不是 deny。
 */
public final class Sandbox {

    private static volatile Path workspaceRoot;

    private Sandbox() {
        // 工具类，禁止实例化
    }

    // ── 初始化 ──

    /** 由 ThoughtCodingContext 初始化时调用一次。 */
    public static void init(String workspacePath) {
        Path raw = Paths.get(workspacePath).toAbsolutePath().normalize();
        try {
            workspaceRoot = raw.toRealPath();
        } catch (Exception e) {
            workspaceRoot = raw;
        }
    }

    private static Path root() {
        Path r = workspaceRoot;
        if (r == null) {
            // 未显式 init 时降级使用当前工作目录
            r = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            try {
                r = r.toRealPath();
            } catch (Exception ignored) {
            }
            workspaceRoot = r;
        }
        return r;
    }

    // ── 路径解析（不做权限决策）──

    /**
     * 解析原始路径为绝对路径（展开 ~、相对于 workspace root 解析、normalize）。
     * 不做越界检查——权限决策由上层 AgentLoop 负责。
     *
     * @param rawPath 用户输入的原始路径（支持 ~，相对/绝对均可）
     * @return 已解析的绝对路径
     */
    public static Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new WorkspaceSecurityException("路径不能为空");
        }

        Path root = root();
        String expanded = expandUserHome(rawPath.trim());
        return root.resolve(expanded).normalize();
    }

    /**
     * 判断给定路径是否在 workspace 范围内。
     */
    public static boolean isWithinWorkspace(Path path) {
        if (path == null) return false;
        Path root = root();
        try {
            return path.toRealPath().startsWith(root);
        } catch (Exception e) {
            // 文件不存在时检查父目录
            Path parent = path.getParent();
            if (parent == null) return false;
            try {
                return parent.toRealPath().startsWith(root);
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private static String expandUserHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
