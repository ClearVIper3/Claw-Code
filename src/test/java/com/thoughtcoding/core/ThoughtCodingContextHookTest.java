package com.thoughtcoding.core;

import com.thoughtcoding.hook.HookRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ThoughtCodingContextHookTest {

    @Test
    void shouldProvideDefaultApplicationHookRegistryWhenBuilderOmitsIt() {
        ThoughtCodingContext context = new ThoughtCodingContext.Builder().build();

        assertNotNull(context.getHookRegistry());
    }

    @Test
    void shouldUseCustomApplicationHookRegistryFromBuilder() {
        HookRegistry registry = new HookRegistry();
        ThoughtCodingContext context = new ThoughtCodingContext.Builder()
                .hookRegistry(registry)
                .build();

        assertSame(registry, context.getHookRegistry());
    }
}
