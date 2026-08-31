package com.thoughtcoding.core;

import com.thoughtcoding.hook.HookRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ThoughtCodingContextHookTest {

    @Test
    void Builder未指定时自动提供应用级HookRegistry() {
        ThoughtCodingContext context = new ThoughtCodingContext.Builder().build();

        assertNotNull(context.getHookRegistry());
    }

    @Test
    void Builder可注入自定义应用级HookRegistry() {
        HookRegistry registry = new HookRegistry();
        ThoughtCodingContext context = new ThoughtCodingContext.Builder()
                .hookRegistry(registry)
                .build();

        assertSame(registry, context.getHookRegistry());
    }
}
