package com.thoughtcoding.cron;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务调度器 —— 独立守护线程轮询 + 触发队列（移植自 s14 的 cron_scheduler_loop + cron_queue）。
 *
 * <p>四层职责：
 * <ol>
 *   <li><b>Scheduler</b>：单条 daemon 线程每 1s 轮询 {@link CronStore}，把匹配到当前时刻的任务入队；</li>
 *   <li><b>Queue</b>：{@link #fired} 队列解耦调度器与消费者（线程安全）；</li>
 *   <li><b>Processor/Consumer</b>：由 REPL 侧守护线程消费（见 ThoughtCodingCommand），
 *       持 agentLock 把 {@code [Scheduled] <prompt>} 注入一次 Agent 回合；</li>
 *   <li><b>Dedup</b>：轮询 1s 一次，同一分钟一个 job 会被匹配 ~60 次，用 {@code lastFired}
 *       分钟标记保证每分钟只入队一次；one-shot 入队后立即从 store 移除，双保险防重复。</li>
 * </ol>
 *
 * <p>容错：单个 job 匹配/取消抛异常被捕获，坏 job 不会杀死整个轮询线程。
 * daemon 线程：即使漏掉 {@link #shutdown()} 也不阻塞 JVM 退出（对齐 {@code BackgroundTaskManager}）。
 */
public class CronScheduler {

    /** 分钟标记格式：防止每日任务在启动第二天后跳过/重复（对齐 s14）。 */
    private static final DateTimeFormatter MINUTE_MARKER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CronStore store;
    private final ConcurrentLinkedQueue<CronJob> fired = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, String> lastFired = new ConcurrentHashMap<>(); // job id → "yyyy-MM-dd HH:mm"

    private Thread pollThread;
    private volatile boolean running;

    public CronScheduler(CronStore store) {
        this.store = store;
    }

    /** 启动守护轮询线程（幂等：已启动则忽略）。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        pollThread = new Thread(this::pollLoop, "cron-scheduler-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /** 停止轮询线程（中断 sleep；daemon 即便漏网也不阻塞 JVM 退出）。 */
    public synchronized void shutdown() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    /** 是否有到点待投递的任务。 */
    public boolean hasFired() {
        return !fired.isEmpty();
    }

    //TODO: 优先CLI前端表现
    /** 取走并清空到点任务（幂等：取出即从队列移除）。 */
    public List<CronJob> drainFired() {
        List<CronJob> out = new ArrayList<>();
        CronJob job;
        while ((job = fired.poll()) != null) {
            out.add(job);
        }
        return out;
    }

    /** 取消一个任务时，连带清掉它已入队但尚未被消费的到点项 + 分钟去重标记，
     *  避免「已取消却仍触发」。仅供显式 cancel 调用——不可放进 pollLoop 的 one-shot 移除路径
     *  （那会在入队瞬间删 store，再清队列会误杀合法的一次性触发）。 */
    public void purgeFired(String jobId) {
        if (jobId == null) {
            return;
        }
        fired.removeIf(job -> jobId.equals(job.getId()));   // ConcurrentLinkedQueue.removeIf 并发安全
        lastFired.remove(jobId);                              // 顺手清去重标记（陈旧无害，清了更干净）
    }

    // ── 轮询线程主体 ──

    private void pollLoop() {
        while (running) {
            try {
                LocalDateTime now = LocalDateTime.now();
                String marker = now.format(MINUTE_MARKER);
                for (CronJob job : store.list()) {
                    try {
                        if (!CronExpression.matches(job.getCron(), now)) {
                            continue;
                        }
                        if (marker.equals(lastFired.get(job.getId()))) {
                            continue; // 同一分钟已触发过（轮询 1s 一次会命中多次）
                        }
                        lastFired.put(job.getId(), marker);
                        fired.add(job);
                        // one-shot：入队后立即移除（持久化），双保险防同分钟重复
                        if (!job.isRecurring()) {
                            store.cancel(job.getId());
                        }
                    } catch (Exception e) {
                        // 单个 job 异常不杀死轮询线程
                        System.err.println("[cron error] job " + job.getId() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                // 整轮列举异常也降级继续
                System.err.println("[cron error] poll loop: " + e.getMessage());
            }
            try {
                TimeUnit.MILLISECONDS.sleep(1000);
            } catch (InterruptedException e) {
                if (!running) {
                    return; // 正常关停
                }
            }
        }
    }
}
