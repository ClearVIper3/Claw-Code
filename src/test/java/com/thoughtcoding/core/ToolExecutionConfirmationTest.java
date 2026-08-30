package com.thoughtcoding.core;

import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ToolExecutionConfirmation 的线程级输入路由测试。 */
class ToolExecutionConfirmationTest {

    @Test
    void 构造后安装的Router也能被后台Agent线程使用() throws Exception {
        AtomicInteger directReads = new AtomicInteger();
        LineReader lineReader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class},
                (proxy, method, args) -> {
                    if ("readLine".equals(method.getName())) {
                        directReads.incrementAndGet();
                        return "direct";
                    }
                    return defaultValue(method.getReturnType());
                });

        AtomicReference<ConsoleInputRouter> routerRef = new AtomicReference<>();
        ToolExecutionConfirmation confirmation = new ToolExecutionConfirmation(
                null, lineReader, null, routerRef::get);

        // 构造时还没有 Router：单次提问模式仍由当前线程直接读取。
        assertEquals("direct", confirmation.readLine("prompt"));
        assertEquals(1, directReads.get());

        // 模拟 AgentTurnRunner 在稍后安装交互模式 Router。
        ConsoleInputRouter router = new ConsoleInputRouter(Thread.currentThread());
        routerRef.set(router);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> confirmation.readLine("prompt"));
        waitUntilPending(router);
        assertTrue(router.deliverLine("1"));

        assertEquals("1", result.get(5, TimeUnit.SECONDS));
        assertEquals(1, directReads.get(), "后台 Agent 线程不应直接调用 JLine");
    }

    @Test
    void 取消等待会立即释放后台Agent线程() throws Exception {
        LineReader lineReader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        ConsoleInputRouter router = new ConsoleInputRouter(Thread.currentThread());
        ToolExecutionConfirmation confirmation = new ToolExecutionConfirmation(
                null, lineReader, null, () -> router);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> confirmation.readLine("prompt"));
        waitUntilPending(router);
        router.cancelPending();

        assertEquals(null, result.get(5, TimeUnit.SECONDS));
    }

    private static void waitUntilPending(ConsoleInputRouter router) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!router.hasPending() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(router.hasPending(), "后台 Agent 线程没有进入输入等待状态");
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
