package com.thoughtcoding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDispatcherTest {

    private final ToolRegistry registry = new ToolRegistry(null);
    private final ToolDispatcher dispatcher = new ToolDispatcher(registry);

    @Test
    void 正常调用会序列化参数并返回工具结果() throws Exception {
        AtomicReference<String> receivedInput = new AtomicReference<>();
        registry.register(new StubTool("echo", input -> {
            receivedInput.set(input);
            return ToolResult.success("ok", 1);
        }));

        ToolResult result = dispatcher.dispatch(call("echo", Map.of("text", "你好")));

        assertTrue(result.isSuccess());
        JsonNode args = new ObjectMapper().readTree(receivedInput.get());
        assertEquals("你好", args.get("text").asText());
    }

    @Test
    void 工具运行时异常会收敛为失败结果而不逃逸() {
        registry.register(new StubTool("broken", input -> {
            throw new IllegalStateException("boom");
        }));

        ToolResult result = assertDoesNotThrow(() -> dispatcher.dispatch(call("broken", Map.of())));

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("工具执行异常 [broken]"));
        assertTrue(result.getError().contains("boom"));
    }

    @Test
    void 工具空返回会收敛为失败结果() {
        registry.register(new StubTool("empty", input -> null));

        ToolResult result = dispatcher.dispatch(call("empty", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("空结果"));
    }

    @Test
    void 取消异常会收敛为当前调用的失败结果() {
        registry.register(new BaseTool("cancellable", "test") {
            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("unexpected", 0);
            }

            @Override
            public ToolResult execute(String input, CancelToken token) {
                token.check();
                return ToolResult.success("unexpected", 0);
            }
        });
        CancelToken token = new CancelToken();
        token.cancel();

        ToolResult result = dispatcher.dispatch(call("cancellable", Map.of()), token);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("工具执行已取消"));
    }

    @Test
    void 参数序列化失败会收敛为失败结果且不调用工具() {
        AtomicReference<String> receivedInput = new AtomicReference<>();
        registry.register(new StubTool("echo", input -> {
            receivedInput.set(input);
            return ToolResult.success("unexpected", 0);
        }));
        Map<String, Object> circular = new HashMap<>();
        circular.put("self", circular);

        ToolResult result = assertDoesNotThrow(() -> dispatcher.dispatch(call("echo", circular)));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("参数序列化失败"));
        assertNull(receivedInput.get());
    }

    @Test
    void 非法或未知调用也始终返回失败结果() {
        assertFalse(dispatcher.dispatch(null).isSuccess());
        assertFalse(dispatcher.dispatch(call(null, Map.of())).isSuccess());
        assertFalse(dispatcher.dispatch(call("missing", Map.of())).isSuccess());
    }

    private ToolCall call(String name, Map<String, Object> parameters) {
        return new ToolCall(name, parameters, null, false, 0, false, "call-1");
    }

    private static final class StubTool extends BaseTool {
        private final ToolAction action;

        private StubTool(String name, ToolAction action) {
            super(name, "test");
            this.action = action;
        }

        @Override
        public ToolResult execute(String input) {
            return action.execute(input);
        }
    }

    @FunctionalInterface
    private interface ToolAction {
        ToolResult execute(String input);
    }
}
