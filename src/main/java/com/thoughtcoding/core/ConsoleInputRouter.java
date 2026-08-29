package com.thoughtcoding.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 终端输入路由器 —— agent 回合后台化后的输入独占问题解法。
 *
 * <p>问题：JLine 的 LineReader 非线程安全。回合转后台线程后，agent 线程的
 * 工具确认（需要读一行）与 REPL 主线程的 {@code readInput}（等待下一条命令）
 * 不能同时 readLine。
 *
 * <p>解法：终端输入统一由 REPL 主线程读取；agent 线程需要一行输入时，
 * 自己打印提示（展示部分不冲突），然后 {@link #awaitLine()} 等待主线程
 * 把用户输入的行 {@link #deliverLine} 投递过来。主线程在 readInput 返回后
 * 优先检查是否有等待者，有则路由给它，无则按 REPL 命令处理。
 *
 * <p>典型时序：主线程阻塞在 thought> → agent 线程打印确认选项并 awaitLine →
 * 用户输入 1/2 → 主线程 readInput 返回 → deliverLine 路由 → agent 线程继续。
 */
public class ConsoleInputRouter {

    /** 等待用户响应确认框的上限；超时视为拒绝（NO），避免 agent 线程永久挂起。 */
    private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000;

    private final Thread ownerThread; // REPL 主线程（输入的唯一读取者）
    private CompletableFuture<String> pending;

    public ConsoleInputRouter(Thread ownerThread) {
        this.ownerThread = ownerThread;
    }

    /** 当前线程是否为 REPL 主线程（是 → 可直接 readLine；否 → 必须走路由）。 */
    public boolean isOnOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    /** 是否有 agent 线程正在等待一行输入。 */
    public synchronized boolean hasPending() {
        return pending != null;
    }

    /**
     * agent 线程调用：等待主线程投递一行输入（调用前自行打印提示文本）。
     *
     * @return 用户输入的行；超时或被取消返回 null（调用方按「拒绝」处理）
     */
    public String awaitLine() {
        CompletableFuture<String> future;
        synchronized (this) {
            if (pending != null) {
                // 已有等待者（不应发生，确认框有全局锁串行化）——直接拒绝后来者
                return null;
            }
            future = new CompletableFuture<>();
            pending = future;
        }
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            synchronized (this) {
                if (pending == future) {
                    pending = null;
                }
            }
        }
    }

    /**
     * REPL 主线程调用：把读到的一行投递给等待者。
     *
     * @return true 表示该行已被等待的确认框消费；false 表示没有等待者（按 REPL 命令处理）
     */
    public synchronized boolean deliverLine(String line) {
        CompletableFuture<String> waiter = pending;
        if (waiter != null) {
            pending = null;
            waiter.complete(line);
            return true;
        }
        return false;
    }

    /** 取消当前等待者（如应用退出时），使其 awaitLine 立即返回 null。 */
    public synchronized void cancelPending() {
        CompletableFuture<String> waiter = pending;
        if (waiter != null) {
            pending = null;
            waiter.complete(null);
        }
    }
}
