package com.thoughtcoding.hook;

import com.thoughtcoding.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateToolCallGuardTest {

    @Test
    void shouldProceedFirstOccurrenceAndRecordIt() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();

        HookResult result = guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "git status"))));

        assertFalse(result.isBlocked());
    }

    @Test
    void shouldBlockImmediateRepeatWithSameToolAndParams() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "git status"))));

        HookResult repeat = guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "git status"))));

        assertTrue(repeat.isBlocked());
        assertTrue(repeat.message().contains(DuplicateToolCallGuard.BLOCK_MESSAGE));
        assertTrue(repeat.message().contains("bash"));
    }

    @Test
    void shouldProceedWhenParamsDiffer() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "git status"))));

        assertFalse(guard.execute(HookContext.forPreTool(null, null,
                call("bash", Map.of("command", "git log")))).isBlocked());
        assertFalse(guard.execute(HookContext.forPreTool(null, null,
                call("bash", Map.of("cwd", "/tmp", "command", "git status")))).isBlocked());
    }

    @Test
    void shouldProceedWhenToolNameDiffers() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        guard.execute(HookContext.forPreTool(null, null, call("read", Map.of("path", "a.txt"))));

        assertFalse(guard.execute(HookContext.forPreTool(null, null,
                call("glob", Map.of("path", "a.txt")))).isBlocked());
    }

    @Test
    void shouldKeepBlockingConsecutiveRepeatsAfterBlock() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x"))));
        assertTrue(guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x")))).isBlocked());

        // 原样重试仍持续被拦，直到模型改变入参或换工具
        assertTrue(guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x")))).isBlocked());
    }

    @Test
    void shouldProceedAfterInterleavedDifferentCall() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x"))));
        assertTrue(guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x")))).isBlocked());
        guard.execute(HookContext.forPreTool(null, null, call("read", Map.of("path", "a.txt"))));

        // 中间隔了不同的调用 → 不算"连续重复"，放行
        assertFalse(guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x")))).isBlocked());
    }

    @Test
    void shouldProceedAfterReset() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x"))));
        assertTrue(guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x")))).isBlocked());
        guard.reset();

        assertFalse(guard.execute(HookContext.forPreTool(null, null, call("bash", Map.of("command", "x")))).isBlocked());
    }

    @Test
    void shouldTreatNullParamsAsEmptyMap() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        guard.execute(HookContext.forPreTool(null, null, call("skill", null)));

        assertTrue(guard.execute(HookContext.forPreTool(null, null, call("skill", Map.of()))).isBlocked());
    }

    @Test
    void shouldNotBlockWhenContextHasNoToolCall() {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();

        // toolCall == null 的防御分支（如误注册到非工具时机）
        assertFalse(guard.execute(HookContext.forStop(null, null)).isBlocked());
    }

    @Test
    void shouldFailOpenByDefault() {
        assertEquals(HookFailurePolicy.FAIL_OPEN, new DuplicateToolCallGuard().failurePolicy());
    }

    @Test
    void shouldRemainConsistentUnderConcurrentExecutions() throws Exception {
        DuplicateToolCallGuard guard = new DuplicateToolCallGuard();
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger proceeded = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread worker = Thread.ofVirtual().unstarted(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                HookResult result = guard.execute(
                        HookContext.forPreTool(null, null, call("bash", Map.of("command", "same"))));
                if (!result.isBlocked()) {
                    proceeded.incrementAndGet();
                }
            });
            workers.add(worker);
            worker.start();
        }
        ready.await();
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        // 锁保护下必然恰好放行一次，其余全部拦截
        assertEquals(1, proceeded.get());
    }

    private ToolCall call(String toolName, Map<String, Object> parameters) {
        return new ToolCall(toolName, parameters, null, false, 0, false,
                UUID.randomUUID().toString());
    }
}
