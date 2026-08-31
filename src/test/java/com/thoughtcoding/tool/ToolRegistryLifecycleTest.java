package com.thoughtcoding.tool;

import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryLifecycleTest {

    @Test
    void 不同所有者的同名工具不能覆盖内置能力() {
        ToolRegistry registry = new ToolRegistry(null);
        BaseTool builtIn = tool("read");
        BaseTool mcpTool = tool("read");

        assertTrue(registry.register(builtIn));
        assertFalse(registry.register("mcp:filesystem", mcpTool));

        assertSame(builtIn, registry.getTool("read"));
        assertEquals(ToolRegistry.APPLICATION_OWNER, registry.ownerOf("read"));
        assertEquals(0, registry.unregisterOwner("mcp:filesystem"));
        assertSame(builtIn, registry.getTool("read"));
    }

    @Test
    void 同一所有者重连时可以刷新工具实例() {
        ToolRegistry registry = new ToolRegistry(null);
        BaseTool oldTool = tool("search");
        BaseTool refreshedTool = tool("search");

        assertTrue(registry.register("mcp:web", oldTool));
        assertTrue(registry.register("mcp:web", refreshedTool));

        assertSame(refreshedTool, registry.getTool("search"));
        assertEquals(1, registry.size());
    }

    @Test
    void 断开服务器只回收属于该服务器的工具() {
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(tool("bash"));
        registry.register("mcp:server-a", tool("a1"));
        registry.register("mcp:server-a", tool("a2"));
        registry.register("mcp:server-b", tool("b1"));

        assertEquals(3, registry.countOwnersWithPrefix("mcp:"));
        assertEquals(2, registry.unregisterOwner("mcp:server-a"));

        assertNull(registry.getTool("a1"));
        assertNull(registry.getTool("a2"));
        assertEquals("mcp:server-b", registry.ownerOf("b1"));
        assertEquals(ToolRegistry.APPLICATION_OWNER, registry.ownerOf("bash"));
        assertEquals(1, registry.countOwnersWithPrefix("mcp:"));
    }

    @Test
    void 动态注册回收与模型读取工具清单可以并发执行() {
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(tool("read"));
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (int worker = 0; worker < 4; worker++) {
            int id = worker;
            tasks.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 100; i++) {
                    String owner = "mcp:server-" + id;
                    registry.register(owner, tool("dynamic-" + id + "-" + i));
                    registry.getToolSpecifications();
                    if (i % 10 == 0) registry.unregisterOwner(owner);
                }
            }));
        }

        assertDoesNotThrow(() -> CompletableFuture.allOf(
                tasks.toArray(CompletableFuture[]::new)).join());
        assertNotNull(registry.getTool("read"));
    }

    private BaseTool tool(String name) {
        return new BaseTool(name, "test") {
            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("ok", 0);
            }
        };
    }
}
