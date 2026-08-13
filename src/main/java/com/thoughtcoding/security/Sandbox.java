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

    /**
     * 每线程工作根覆盖（s18 worktree 隔离）：后台队友认领到绑定了 worktree 的任务时，
     * 把自己的相对路径解析根临时切到 {@code .worktrees/<name>}，让 read/write/edit/glob/bash
     * 都落在该 worktree 副本里，互不踩踏。主（lead）线程从不设置它 → 始终仓库根。
     * 仅在派发工具的执行线程上 set/clear（Teammate），不会跨线程泄漏。
     */
    private static final ThreadLocal<Path> THREAD_ROOT = new ThreadLocal<>();

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
        // 线程级覆盖优先（队友的 worktree 隔离 cwd）；未设置则回退全局 workspace 根
        Path override = THREAD_ROOT.get();
        if (override != null) {
            return override;
        }
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

    // ── 每线程根覆盖（s18 worktree 隔离）──

    /**
     * 为本线程设置工作根覆盖（队友 worktree 的绝对路径）。调用方必须在同一线程的
     * try/finally 中配对 {@link #clearThreadRoot()}，避免长驻守护线程残留旧条目。
     */
    public static void setThreadRoot(Path root) {
        THREAD_ROOT.set(root);
    }

    /** 清除本线程的工作根覆盖（用 remove 而非 set(null)，避免守护线程内存滞留）。 */
    public static void clearThreadRoot() {
        THREAD_ROOT.remove();
    }

    /**
     * 当前线程的有效工作根：设置了线程覆盖则返回它（队友 worktree），否则返回全局 workspace 根。
     * 供 bash 等不走 {@link #resolve} 的工具查询本线程应使用的 cwd。
     */
    public static Path currentRoot() {
        return root();
    }

    /**
     * 未被线程覆盖影响的真实仓库根（全局 workspaceRoot）。安全边界判定（{@link #isWithinWorkspace}）
     * 锚定它——worktree 嵌在仓库内，路径仍判定为界内。
     */
    public static Path repoRoot() {
        Path r = workspaceRoot;
        if (r == null) {
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
     * 安全边界锚定真实仓库根（{@link #repoRoot}），不受线程级 worktree 覆盖影响——
     * worktree 嵌在仓库内，其路径仍判定为界内。
     */
    public static boolean isWithinWorkspace(Path path) {
        if (path == null) return false;
        Path root = repoRoot();
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
