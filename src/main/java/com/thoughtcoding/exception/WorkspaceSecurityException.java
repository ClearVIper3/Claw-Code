package com.thoughtcoding.exception;

/**
 * 沙箱路径越界异常。
 *
 * 当工具尝试访问 workspace 之外的路径时，由 {@link com.thoughtcoding.tool.Sandbox#safePath}
 * 抛出。工具的 execute() 方法在 catch 块中捕获此异常并返回 ToolResult.error。
 */
public class WorkspaceSecurityException extends RuntimeException {

    public WorkspaceSecurityException(String message) {
        super(message);
    }
}
