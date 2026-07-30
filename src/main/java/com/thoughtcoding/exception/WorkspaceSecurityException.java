package com.thoughtcoding.exception;

/**
 * 沙箱路径异常。
 *
 * 由 {@link com.thoughtcoding.security.Sandbox#resolve} 在路径为空白时抛出。
 */
public class WorkspaceSecurityException extends RuntimeException {

    public WorkspaceSecurityException(String message) {
        super(message);
    }
}
