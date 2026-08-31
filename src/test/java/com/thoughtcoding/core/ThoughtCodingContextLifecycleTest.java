package com.thoughtcoding.core;

import com.thoughtcoding.mcp.MCPService;
import com.thoughtcoding.mcp.MCPToolManager;
import com.thoughtcoding.service.AIService;
import com.thoughtcoding.tool.ToolRegistry;
import com.thoughtcoding.ui.ThoughtCodingUI;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ThoughtCodingContextLifecycleTest {

    @Test
    void shouldCloseApplicationResourcesInOrderAndRemainIdempotent() {
        AIService aiService = mock(AIService.class);
        SubAgentExecutor subAgents = mock(SubAgentExecutor.class);
        MCPToolManager mcpManager = mock(MCPToolManager.class);
        MCPService mcpService = mock(MCPService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        ThoughtCodingUI ui = mock(ThoughtCodingUI.class);
        ConsoleInputRouter router = mock(ConsoleInputRouter.class);
        ThoughtCodingContext context = context(
                aiService, subAgents, mcpManager, mcpService, tools, ui);
        context.setConsoleInputRouter(router);

        context.close();
        context.close();

        InOrder order = inOrder(router, aiService, subAgents, mcpManager, mcpService, tools, ui);
        order.verify(router).cancelPending();
        order.verify(aiService).stopCurrentGeneration();
        order.verify(subAgents).shutdown();
        order.verify(mcpManager).shutdown();
        order.verify(mcpService).shutdown();
        order.verify(tools).unregisterOwnersWithPrefix("mcp:");
        order.verify(ui).close();
        verify(aiService, times(1)).stopCurrentGeneration();
        verify(ui, times(1)).close();
        assertTrue(context.isClosed());
    }

    @Test
    void shouldContinueClosingResourcesWhenOneShutdownFails() {
        AIService aiService = mock(AIService.class);
        SubAgentExecutor subAgents = mock(SubAgentExecutor.class);
        MCPToolManager mcpManager = mock(MCPToolManager.class);
        MCPService mcpService = mock(MCPService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        ThoughtCodingUI ui = mock(ThoughtCodingUI.class);
        doThrow(new IllegalStateException("boom")).when(subAgents).shutdown();
        ThoughtCodingContext context = context(
                aiService, subAgents, mcpManager, mcpService, tools, ui);

        assertDoesNotThrow(context::close);

        verify(mcpManager).shutdown();
        verify(mcpService).shutdown();
        verify(tools).unregisterOwnersWithPrefix("mcp:");
        verify(ui).close();
    }

    @Test
    void shouldSafelyCloseContextBuiltWithoutOptionalResources() {
        ThoughtCodingContext context = new ThoughtCodingContext.Builder().build();

        assertDoesNotThrow(context::close);
        assertTrue(context.isClosed());
    }

    private ThoughtCodingContext context(AIService aiService,
                                         SubAgentExecutor subAgents,
                                         MCPToolManager mcpManager,
                                         MCPService mcpService,
                                         ToolRegistry tools,
                                         ThoughtCodingUI ui) {
        return new ThoughtCodingContext.Builder()
                .aiService(aiService)
                .subAgentExecutor(subAgents)
                .mcpToolManager(mcpManager)
                .mcpService(mcpService)
                .toolRegistry(tools)
                .ui(ui)
                .build();
    }
}
