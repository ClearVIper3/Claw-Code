package com.thoughtcoding.mcp;

import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MCPServiceLifecycleTest {

    @Test
    void shouldStoreAndRemoveToolSnapshotsByServer() {
        MCPService service = new MCPService();
        BaseTool a1 = tool("a1");
        BaseTool a2 = tool("a2");
        BaseTool b1 = tool("b1");
        service.rememberTools("server-a", List.of(a1, a2));
        service.rememberTools("server-b", List.of(b1));

        assertEquals(3, service.getMCPToolCount());
        assertEquals(2, service.getToolsForServer("server-a").size());

        service.disconnectServer("server-a");

        assertEquals(1, service.getMCPToolCount());
        assertSame(b1, service.getToolsForServer("server-b").get(0));
    }

    @Test
    void shouldReplaceOldToolsWhenSameServerReconnects() {
        MCPService service = new MCPService();
        BaseTool oldTool = tool("old");
        BaseTool refreshedTool = tool("new");
        service.rememberTools("server", List.of(oldTool));

        service.rememberTools("server", List.of(refreshedTool));

        assertEquals(1, service.getMCPToolCount());
        assertSame(refreshedTool, service.getToolsForServer("server").get(0));
    }

    @Test
    void shouldPreserveToolsFromAllServersWhenNamesCollideDuringFlattening() {
        MCPService service = new MCPService();
        service.rememberTools("server-a", List.of(tool("search")));
        service.rememberTools("server-b", List.of(tool("search")));

        assertEquals(2, service.getMCPTools().size());
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
