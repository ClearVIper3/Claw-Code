package com.thoughtcoding.hook;

/**
 * 单个 Hook 动作的执行结果。
 *
 * <pre>
 *   proceed()          → 继续执行后续动作 / 放行本次操作
 *   block(msg)         → 阻断：串行链路提前终止，本次操作被拒绝（PreToolUse/UserPromptSubmit 生效）
 *   continueLoop(msg)  → STOP 时机专用：要求循环强制续跑，不退出
 * </pre>
 */
public record HookResult(Decision decision, String message) {

    public enum Decision {
        /** 放行，继续后续动作。 */
        PROCEED,
        /** 阻断当前操作并终止本时机的后续动作。 */
        BLOCK,
        /** STOP 时机：阻止退出、强制续跑。 */
        CONTINUE_LOOP
    }

    public static final HookResult PROCEED = new HookResult(Decision.PROCEED, null);

    public static HookResult block(String message) {
        return new HookResult(Decision.BLOCK, message);
    }

    public static HookResult continueLoop(String message) {
        return new HookResult(Decision.CONTINUE_LOOP, message);
    }

    public boolean isBlocked() { return decision == Decision.BLOCK; }
    public boolean isContinueLoop() { return decision == Decision.CONTINUE_LOOP; }
}
