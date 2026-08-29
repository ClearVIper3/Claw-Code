package com.thoughtcoding.core;

/**
 * 协作式取消的检查点异常：{@link CancelToken#check()} 在已取消时抛出。
 *
 * <p>设计为 RuntimeException：工具实现里的检查点无需到处声明 throws，
 * 但各层捕获后必须转化为<b>配对的</b> tool 结果或兜底结论，绝不能让它
 * 逃逸到 AgentLoop 之外破坏「assistant 工具调用 ↔ tool 结果」的 id 配对。
 */
public class CancelledException extends RuntimeException {
    public CancelledException(String message) {
        super(message);
    }
}
