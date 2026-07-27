package com.thoughtcoding.hook;

/**
 * Hook 触发时机。
 *
 * <pre>
 *   USER_PROMPT_SUBMIT  用户输入提交后、进入 LLM 前  —— 输入验证、注入上下文
 *   PRE_TOOL_USE        工具执行前                  —— 权限检查、日志记录
 *   POST_TOOL_USE       工具执行后                  —— 副作用（自动 git add 等）、输出检查
 *   STOP                循环即将退出时              —— 收尾清理（可强制续跑）
 * </pre>
 *
 * 同一时机内注册的多个动作按注册顺序 <b>串行</b> 执行。
 */
public enum HookType {
    USER_PROMPT_SUBMIT,
    PRE_TOOL_USE,
    POST_TOOL_USE,
    STOP
}
