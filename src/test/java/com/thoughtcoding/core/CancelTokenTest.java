package com.thoughtcoding.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CancelToken 协作式取消框架的单元测试。
 */
class CancelTokenTest {

    @Test
    void shouldStartNotCancelledAndAllowCheck() {
        CancelToken token = new CancelToken();
        assertFalse(token.isCancelled());
        assertDoesNotThrow(token::check);
    }

    @Test
    void shouldThrowCancelledExceptionAfterCancellation() {
        CancelToken token = new CancelToken();
        token.cancel();
        assertTrue(token.isCancelled());
        assertThrows(CancelledException.class, token::check);
    }

    @Test
    void shouldInvokeAllRegisteredCallbacksOnCancellation() {
        CancelToken token = new CancelToken();
        AtomicInteger fired = new AtomicInteger();
        token.onCancel(fired::incrementAndGet);
        token.onCancel(fired::incrementAndGet);

        token.cancel();
        assertEquals(2, fired.get(), "两个回调都应被触发");
    }

    @Test
    void shouldInvokeCallbacksOnlyOnceWhenCancelledRepeatedly() {
        CancelToken token = new CancelToken();
        AtomicInteger fired = new AtomicInteger();
        token.onCancel(fired::incrementAndGet);

        token.cancel();
        token.cancel();
        token.cancel();
        assertEquals(1, fired.get(), "重复 cancel 不应重复触发回调");
    }

    @Test
    void shouldInvokeCallbackImmediatelyWhenRegisteredAfterCancellation() {
        CancelToken token = new CancelToken();
        token.cancel();

        AtomicInteger fired = new AtomicInteger();
        token.onCancel(fired::incrementAndGet);
        assertEquals(1, fired.get(), "注册时已取消则立即内联执行");
    }

    @Test
    void shouldContinueInvokingCallbacksWhenOneCallbackFails() {
        CancelToken token = new CancelToken();
        AtomicInteger fired = new AtomicInteger();
        token.onCancel(() -> { throw new RuntimeException("boom"); });
        token.onCancel(fired::incrementAndGet);

        assertDoesNotThrow(token::cancel);
        assertEquals(1, fired.get(), "第一个回调抛异常，第二个仍应执行");
    }

    @Test
    void shouldInvokeCallbackExactlyOnceDuringConcurrentRegistrationAndCancellation() throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            CancelToken token = new CancelToken();
            AtomicInteger fired = new AtomicInteger();

            Thread canceller = new Thread(token::cancel);
            Thread registrant = new Thread(() -> token.onCancel(fired::incrementAndGet));

            canceller.start();
            registrant.start();
            canceller.join();
            registrant.join();

            assertEquals(1, fired.get(),
                    "第 " + round + " 轮：注册/取消竞争下回调必须恰好执行一次");
        }
    }
}
