package com.thoughtcoding.tool;

import com.thoughtcoding.exception.WorkspaceSecurityException;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 通用沙箱 —— 校验文件路径必须在 workspace 范围内。
 *
 * <pre>
 * // 工具内用法（替代原来的 Paths.get(expandUserHome(...)).toAbsolutePath()）：
 * Path path = Sandbox.safePath(p.toString());
 * </pre>
 *
 * 越界时抛出 {@link WorkspaceSecurityException}，由工具的现有 {@code catch (Exception e)} 转为 ToolResult.error。
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

    // ── 核心方法 ──

    /**
     * 校验并返回安全路径。
     *
     * @param rawPath 用户输入的原始路径（支持 ~，相对/绝对均可）
     * @return 已解析的绝对路径
     * @throws WorkspaceSecurityException 路径在 workspace 之外
     */
    public static Path safePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new WorkspaceSecurityException("路径不能为空");
        }

        Path root = root();
        String expanded = expandUserHome(rawPath.trim());
        Path target = root.resolve(expanded).normalize();

        try {
            Path real = target.toRealPath();
            if (!real.startsWith(root)) {
                throw new WorkspaceSecurityException(
                        "路径越界: '" + rawPath + "' → " + real + "（workspace: " + root + "）");
            }
            return real;
        } catch (WorkspaceSecurityException e) {
            throw e;
        } catch (Exception fileNotExist) {
            return validateParent(rawPath, target, root);
        }
    }

    /** 文件不存在时，退而校验父目录。 */
    private static Path validateParent(String rawPath, Path target, Path root) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new WorkspaceSecurityException(
                    "路径越界: '" + rawPath + "' 解析为根目录（workspace: " + root + "）");
        }
        try {
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(root)) {
                throw new WorkspaceSecurityException(
                        "路径越界: '" + rawPath + "' → 父目录 " + realParent + "（workspace: " + root + "）");
            }
        } catch (WorkspaceSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkspaceSecurityException("无法解析路径: '" + rawPath + "' — " + e.getMessage());
        }
        return target;
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
