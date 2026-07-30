package com.thoughtcoding.security;

import com.thoughtcoding.core.ToolExecutionConfirmation;
import com.thoughtcoding.hook.Hook;
import com.thoughtcoding.hook.HookContext;
import com.thoughtcoding.hook.HookResult;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolExecution;

/**
 * 权限检查 Hook —— 将 {@link PermissionGate} 的检查逻辑注册到 PRE_TOOL_USE 时机。
 *
 * <p>执行流程：
 * <ol>
 *   <li>调用 {@link PermissionGate#check} 获取权限决策</li>
 *   <li>DENY → {@link HookResult#block}，阻断工具执行</li>
 *   <li>WARN → 非自动模式下弹出交互式确认框，用户拒绝则阻断</li>
 *   <li>ALLOW / 自动模式 → 放行</li>
 * </ol>
 *
 * <p>通过 Hook 机制收敛权限检查，使 {@code AgentLoop} 不再内联调用 {@code PermissionGate}，
 * 统一走 {@code HookRegistry.fire(PRE_TOOL_USE)} 一个入口完成权限决策。
 */
public class PermissionHook implements Hook {

    private final ToolExecutionConfirmation confirmation;

    /**
     * @param confirmation 交互式确认组件（由 AgentLoop 持有），用于 WARN 场景弹框
     */
    public PermissionHook(ToolExecutionConfirmation confirmation) {
        this.confirmation = confirmation;
    }

    @Override
    public HookResult execute(HookContext context) {
        ToolCall call = context.getToolCall();
        if (call == null) {
            return HookResult.proceed();
        }

        PermissionResult perm = PermissionGate.check(call.getToolName(), call.getParameters());

        // ── DENY：硬拒绝，直接阻断 ──
        if (perm.type() == PermissionResult.Type.DENY) {
            return HookResult.block(perm.message());
        }

        // ── WARN：非自动模式弹出确认框 ──
        if (perm.type() == PermissionResult.Type.WARN && !confirmation.isAutoApproveMode()) {
            ToolExecution exec = new ToolExecution(
                    call.getToolName(),
                    call.getDescription() != null ? call.getDescription() : "执行工具操作",
                    call.getParameters(),
                    true);
            ToolExecutionConfirmation.ActionType action = confirmation.askConfirmationWithOptions(exec);
            if (action == ToolExecutionConfirmation.ActionType.NO) {
                return HookResult.block("用户拒绝执行该工具。");
            }
        }

        // ── ALLOW / 自动模式放行 ──
        return HookResult.proceed();
    }

    @Override
    public String name() {
        return "PermissionCheck";
    }
}
