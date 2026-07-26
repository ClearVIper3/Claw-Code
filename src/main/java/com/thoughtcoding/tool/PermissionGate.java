package com.thoughtcoding.tool;

import com.thoughtcoding.exception.WorkspaceSecurityException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 权限管道 —— AgentLoop 的唯一决策入口。
 *
 * <pre>
 * 调用方式：
 *   PermissionResult perm = PermissionGate.check(toolName, params);
 *   if (perm.type() == DENY)  → 直接报错跳过
 *   if (perm.type() == WARN)  → 弹确认
 *   // ALLOW → 静默放行
 * </pre>
 *
 * 决策规则：
 *   read / glob  → 路径越界则 WARN，否则 ALLOW
 *   write / edit → 固定 WARN
 *   bash         → Gate 1 硬拒绝 DENY，否则固定 WARN（危险模式追加提示）
 *   未知工具      → WARN
 */
public final class PermissionGate {

    private PermissionGate() {}

    // ═══════════════ Gate 1: 硬拒绝列表 ═══════════════

    private static final List<String> BASH_DENY_PATTERNS = List.of(
        "rm -rf /", "sudo ", "shutdown", "reboot", "mkfs",
        "dd if=", "> /dev/sda", "format ", "del /f /s", "rd /s /q"
    );

    private static PermissionResult checkBashDeny(String command) {
        if (command == null || command.isBlank()) return null;
        String lower = command.toLowerCase();
        for (String pattern : BASH_DENY_PATTERNS) {
            if (lower.contains(pattern)) {
                return PermissionResult.deny(
                    "⛔ 命令被阻止: '" + pattern + "' 在拒绝列表中");
            }
        }
        return null;
    }

    // ═══════════════ Gate 2: 规则匹配 ═══════════════

    // ── read / glob：路径越界才确认 ──
    private static PermissionResult checkReadPath(Map<String, Object> params) {
        String pathStr = paramString(params, "path");
        if (pathStr == null || pathStr.isBlank()) return PermissionResult.ALLOW;

        try {
            Path resolved = Sandbox.resolve(pathStr);
            if (!Sandbox.isWithinWorkspace(resolved)) {
                return PermissionResult.warn(
                    "⚠️ 路径在 workspace 之外: " + resolved);
            }
        } catch (WorkspaceSecurityException e) {
            // 路径为空/非法 → 交给工具侧报具体错误，权限不做拦截
            return PermissionResult.ALLOW;
        }
        return PermissionResult.ALLOW;
    }

    // ── bash：先过 Gate 1 拒绝列表，再追加危险模式提示 ──
    private static PermissionResult checkBash(Map<String, Object> params) {
        String command = paramString(params, "command");

        // Gate 1: 硬拒绝
        PermissionResult deny = checkBashDeny(command);
        if (deny != null) return deny;

        // 固定确认，有危险模式则追加提示
        String extra = bashWarning(command);
        return PermissionResult.warn(
            "⚠️ 将执行 shell 命令" + (extra.isEmpty() ? "" : " —— " + extra));
    }

    /** 返回危险模式描述，无匹配返回空串。 */
    private static String bashWarning(String command) {
        if (command == null || command.isBlank()) return "";
        String lower = command.toLowerCase();

        if (lower.contains("rm ") || lower.contains("del "))
            return "包含删除操作";
        if (lower.contains("> /etc/") || lower.contains("> c:\\windows"))
            return "写入系统目录";
        if (lower.contains("chmod 777"))
            return "修改关键权限";
        if (lower.contains("git push --force") || lower.contains("git push -f"))
            return "强制推送";
        return "";
    }

    // ═══════════════ 入口 ═══════════════

    /**
     * 一次性权限决策。
     *
     * @return DENY → 拒绝执行；WARN → 弹确认；ALLOW → 静默放行
     */
    public static PermissionResult check(String toolName, Map<String, Object> params) {
        if (toolName == null) return PermissionResult.warn("⚠️ 未知工具");

        return switch (toolName) {
            case "read", "glob" -> checkReadPath(params);
            case "write"        -> PermissionResult.warn("⚠️ 将写入文件");
            case "edit"         -> PermissionResult.warn("⚠️ 将修改文件");
            case "bash"         -> checkBash(params);
            default             -> PermissionResult.warn("⚠️ 未知工具: " + toolName);
        };
    }

    // ── 工具方法 ──

    private static String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }
}
