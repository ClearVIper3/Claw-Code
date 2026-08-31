package com.thoughtcoding.core;

import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.tool.ToolRegistry;
import com.thoughtcoding.ui.ThoughtCodingUI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DirectCommandExecutor 功能测试
 * 测试新增的高级功能：自然语言识别、批量操作、智能上下文
 */
public class DirectCommandExecutorTest {

    private ProjectContext projectContext;

    @BeforeEach
    public void setUp() {
        projectContext = new ProjectContext(System.getProperty("user.dir"));
    }

    @Test
    public void testProjectContextDetection() {
        // 测试项目类型检测
        ProjectContext.ProjectType type = projectContext.getProjectType();
        assertNotNull(type, "项目类型不应为空");

        System.out.println("✅ 项目类型检测测试通过");
        System.out.println("检测到的项目类型: " + type.getDisplayName());
    }

    @Test
    public void testSmartCommandTranslation() {
        // 测试智能命令转换
        String buildCmd = projectContext.smartTranslate("build");
        String testCmd = projectContext.smartTranslate("test");
        String cleanCmd = projectContext.smartTranslate("clean");

        System.out.println("✅ 智能命令转换测试通过");
        System.out.println("构建命令: " + buildCmd);
        System.out.println("测试命令: " + testCmd);
        System.out.println("清理命令: " + cleanCmd);

        // 根据项目类型验证
        ProjectContext.ProjectType type = projectContext.getProjectType();
        if (type == ProjectContext.ProjectType.MAVEN) {
            assertEquals("mvn package", buildCmd);
            assertEquals("mvn test", testCmd);
            assertEquals("mvn clean", cleanCmd);
        }
    }

    @Test
    public void testProjectSummary() {
        // 测试项目信息摘要
        String summary = projectContext.getSummary();
        assertNotNull(summary, "项目摘要不应为空");
        assertTrue(summary.contains("项目类型"), "摘要应包含项目类型");

        System.out.println("✅ 项目摘要测试通过");
        System.out.println(summary);
    }

    @Test
    public void testRecommendedCommands() {
        // 测试推荐命令
        String[] recommendations = projectContext.getRecommendedCommands();
        assertNotNull(recommendations, "推荐命令不应为空");

        System.out.println("✅ 推荐命令测试通过");
        System.out.println("推荐命令数量: " + recommendations.length);
        for (String cmd : recommendations) {
            System.out.println("  • " + cmd);
        }
    }

    @Test
    void 直接命令命中硬拒绝规则时不会执行工具也不会弹确认() {
        AtomicInteger executions = new AtomicInteger();
        Fixture fixture = fixtureWithBash(executions);

        ToolResult result = fixture.executor.runBash("shutdown now");

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("被阻止"));
        assertEquals(0, executions.get());
        verify(fixture.confirmation, never()).askConfirmationWithOptions(any());
    }

    @Test
    void 直接命令被用户拒绝时不会执行注册表工具() {
        AtomicInteger executions = new AtomicInteger();
        Fixture fixture = fixtureWithBash(executions);
        when(fixture.confirmation.askConfirmationWithOptions(any()))
                .thenReturn(ToolExecutionConfirmation.ActionType.NO);

        ToolResult result = fixture.executor.runBash("git status");

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("用户拒绝"));
        assertEquals(0, executions.get());
    }

    @Test
    void 直接命令确认后通过统一Dispatcher执行注册表工具() {
        AtomicInteger executions = new AtomicInteger();
        Fixture fixture = fixtureWithBash(executions);
        when(fixture.confirmation.askConfirmationWithOptions(any()))
                .thenReturn(ToolExecutionConfirmation.ActionType.YES);

        ToolResult result = fixture.executor.runBash("git status");

        assertTrue(result.isSuccess());
        assertEquals(1, executions.get());
        verify(fixture.confirmation).askConfirmationWithOptions(any());
    }

    private Fixture fixtureWithBash(AtomicInteger executions) {
        ThoughtCodingContext context = mock(ThoughtCodingContext.class);
        ThoughtCodingUI ui = mock(ThoughtCodingUI.class);
        ToolExecutionConfirmation confirmation = mock(ToolExecutionConfirmation.class);
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(new BaseTool("bash", "test bash") {
            @Override
            public ToolResult execute(String input) {
                executions.incrementAndGet();
                return ToolResult.success("ok", 1);
            }
        });
        when(context.getUi()).thenReturn(ui);
        when(context.getToolRegistry()).thenReturn(registry);
        when(context.getHookRegistry()).thenReturn(new HookRegistry());

        return new Fixture(new DirectCommandExecutor(context, confirmation), confirmation);
    }

    private record Fixture(DirectCommandExecutor executor,
                           ToolExecutionConfirmation confirmation) {}
}

