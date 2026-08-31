package com.thoughtcoding.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubAgentExecutor 并行调度的单元测试：并发上限（Semaphore）、
 * 后台任务生命周期、结论 drain 语义。
 */
class SubAgentExecutorTest {

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "任务未在超时内完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("测试线程被中断");
        }
    }

    @Test
    void shouldNotExceedConfiguredConcurrencyLimit() {
        SubAgentExecutor executor = new SubAgentExecutor(2, null);
        try {
            int tasks = 6;
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(tasks);

            for (int i = 0; i < tasks; i++) {
                executor.submitForeground(() -> {
                    int now = running.incrementAndGet();
                    maxConcurrent.accumulateAndGet(now, Math::max);
                    Thread.sleep(200); // 模拟子代理工作
                    running.decrementAndGet();
                    done.countDown();
                    return null;
                });
            }
            awaitQuietly(done);

            assertTrue(maxConcurrent.get() <= 2,
                    "同时运行的任务数(" + maxConcurrent.get() + ")不应超过上限 2");
        } catch (Exception e) {
            fail(e);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldRunIndependentTasksInParallel() {
        SubAgentExecutor executor = new SubAgentExecutor(3, null);
        try {
            int tasks = 3;
            long perTaskMs = 300;
            CountDownLatch done = new CountDownLatch(tasks);
            long start = System.currentTimeMillis();

            for (int i = 0; i < tasks; i++) {
                executor.submitForeground(() -> {
                    Thread.sleep(perTaskMs);
                    done.countDown();
                    return null;
                });
            }
            awaitQuietly(done);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < perTaskMs * tasks,
                    "3 个并行任务耗时 " + elapsed + "ms，应显著小于串行的 " + (perTaskMs * tasks) + "ms");
        } catch (Exception e) {
            fail(e);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldDrainCompletedBackgroundTaskResultOnce() {
        SubAgentExecutor executor = new SubAgentExecutor(2, null);
        try {
            SubAgentExecutor.BackgroundTask task =
                    executor.startBackground("调研", t -> "结论内容");

            // 轮询等待任务完成（生产环境由 UI 通知，这里直接等状态翻转）
            long deadline = System.currentTimeMillis() + 10_000;
            while (task.status() == SubAgentExecutor.BackgroundTask.Status.RUNNING
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            assertEquals(SubAgentExecutor.BackgroundTask.Status.DONE, task.status());
            assertEquals("结论内容", task.conclusion());

            List<SubAgentExecutor.BackgroundTask> drained = executor.drainCompleted();
            assertEquals(1, drained.size(), "应取回一个已完成任务");
            assertEquals(task.id(), drained.get(0).id());

            assertTrue(executor.drainCompleted().isEmpty(), "drain 应是一次性取空");
        } catch (Exception e) {
            fail(e);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldMarkFailedBackgroundTaskAndExposeResultForDrain() {
        SubAgentExecutor executor = new SubAgentExecutor(1, null);
        try {
            executor.startBackground("会失败", t -> {
                throw new IllegalStateException("boom");
            });

            List<SubAgentExecutor.BackgroundTask> drained = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            while (drained.isEmpty() && System.currentTimeMillis() < deadline) {
                drained.addAll(executor.drainCompleted());
                if (drained.isEmpty()) {
                    Thread.sleep(20);
                }
            }

            assertEquals(1, drained.size());
            assertEquals(SubAgentExecutor.BackgroundTask.Status.FAILED, drained.get(0).status());
            assertTrue(drained.get(0).conclusion().contains("boom"));
        } catch (Exception e) {
            fail(e);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldCancelRunningBackgroundTasksOnShutdown() {
        SubAgentExecutor executor = new SubAgentExecutor(1, null);
        CountDownLatch entered = new CountDownLatch(1);
        SubAgentExecutor.BackgroundTask task = executor.startBackground("长任务", t -> {
            entered.countDown();
            for (int i = 0; i < 1000 && !t.isCancelled(); i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return "收到取消后退出";
        });

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "任务应已开始运行");
            executor.shutdown();
            assertTrue(task.token().isCancelled(), "shutdown 应取消任务私有 token");
        } catch (Exception e) {
            fail(e);
        }
    }
}
