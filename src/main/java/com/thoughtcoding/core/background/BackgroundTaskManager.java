package com.thoughtcoding.core.background;

import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台任务管理器（移植自教学模块 s13 的 thread 化异步执行）。
 *
 * <p>把耗时工具调用（如 bash 构建/测试/长轮询）卸载到守护线程池异步执行：
 * Agent 立刻拿到占位 tool_result 继续推进，命令完成后其输出以
 * <task_notification> 消息注入对话、由下一轮 LLM 感知并响应。</p>
 *
 * <p>线程安全约定：worker 只调用 {@link ToolDispatcher#dispatch}（内部查表 + 执行），
 * 不触碰 UI/历史；结果写入 {@link BgTask} 时<b>先写 {@code result} 再置 {@code done}</b>，
 * 两者皆 volatile，保证读取方看到 {@code done==true} 时必然能看到完整结果。</p>
 */
public class BackgroundTaskManager {

    /** 一条后台任务的运行时状态。 */
    public static final class BgTask {
        public final String id;           // 如 bg_0001
        public final String toolName;
        public final String label;        // 展示用（如命令文本）
        public final ToolCall call;       // 保留给 PostToolUse hook + providerCallId 配对
        public volatile boolean done;     // 完成后置 true（先于它写入 result）
        public volatile ToolResult result;

        BgTask(String id, ToolCall call, String label) {
            this.id = id;
            this.toolName = call.getToolName();
            this.label = label;
            this.call = call;
        }
    }

    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "bg-task-worker-" + n.incrementAndGet());
            t.setDaemon(true);   // 守护线程：绝不让后台任务阻塞 JVM 退出
            return t;
        }
    });
    private final Map<String, BgTask> registry = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    /** 派发一条后台任务，立即返回任务 id。 */
    public String dispatch(ToolCall call, ToolDispatcher dispatcher, String label) {
        String id = String.format("bg_%04d", counter.incrementAndGet());
        BgTask task = new BgTask(id, call, label);
        registry.put(id, task);
        pool.submit(() -> {
            ToolResult r;
            try {
                r = dispatcher.dispatch(call);
            } catch (Throwable t) {
                r = ToolResult.error("后台任务异常: " + t.getMessage(), 0);
            }
            task.result = r;   // 先写结果
            task.done = true;  // 再置完成标记（volatile 保证可见性）
        });
        return id;
    }

    /** 取走已完成且未上报的任务（上报一次，幂等：取走即从注册表移除）。 */
    public List<BgTask> drainCompleted() {
        List<BgTask> out = new ArrayList<>();
        for (BgTask t : registry.values()) {
            if (t.done) {
                registry.remove(t.id);
                out.add(t);
            }
        }
        return out;
    }

    public boolean hasRunning() {
        return !registry.isEmpty();
    }

    public int runningCount() {
        return registry.size();
    }

    /** 终止 worker 线程池（中断在跑任务）。守护线程即便漏网也不会阻塞 JVM 退出。 */
    public void shutdown() {
        pool.shutdownNow();
    }
}
