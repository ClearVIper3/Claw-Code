package com.thoughtcoding.hook;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.security.PermissionHook;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookRegistryTest {

    @Test
    void 用户输入Hook可改写Prompt并按注册顺序注入上下文() {
        List<ChatMessage> history = new ArrayList<>();
        HookContext context = HookContext.forUserPrompt(null, history, "原始问题");
        HookRegistry registry = new HookRegistry()
                .register(HookType.USER_PROMPT_SUBMIT, "rewrite", hookContext -> {
                    hookContext.setPrompt("改写后的问题");
                    hookContext.injectContext("项目约束 A");
                    return HookResult.proceed();
                })
                .register(HookType.USER_PROMPT_SUBMIT, "augment", hookContext -> {
                    hookContext.injectContext("项目约束 B");
                    return HookResult.proceed();
                });

        HookResult result = registry.fire(context);

        assertFalse(result.isBlocked());
        assertEquals("改写后的问题\n\n[Hook 注入上下文]\n项目约束 A\n\n项目约束 B",
                context.buildPromptForModel());
    }

    @Test
    void 阻断结果会终止后续Hook() {
        AtomicBoolean laterExecuted = new AtomicBoolean(false);
        HookRegistry registry = new HookRegistry()
                .register(HookType.USER_PROMPT_SUBMIT, "block", context -> HookResult.block("拒绝"))
                .register(HookType.USER_PROMPT_SUBMIT, "later", context -> {
                    laterExecuted.set(true);
                    return HookResult.proceed();
                });

        HookResult result = registry.fire(HookContext.forUserPrompt(null, List.of(), "prompt"));

        assertTrue(result.isBlocked());
        assertEquals("拒绝", result.message());
        assertFalse(laterExecuted.get());
    }

    @Test
    void 普通Hook异常时FailOpen并继续动作链() {
        AtomicBoolean laterExecuted = new AtomicBoolean(false);
        HookRegistry registry = new HookRegistry()
                .register(HookType.POST_TOOL_USE, "broken", context -> {
                    throw new IllegalStateException("观测服务不可用");
                })
                .register(HookType.POST_TOOL_USE, "later", context -> {
                    laterExecuted.set(true);
                    return HookResult.proceed();
                });

        HookResult result = registry.fire(HookContext.forPostTool(null, List.of(), null, null));

        assertFalse(result.isBlocked());
        assertTrue(laterExecuted.get());
    }

    @Test
    void 安全Hook异常时FailClosed并终止动作链() {
        AtomicBoolean laterExecuted = new AtomicBoolean(false);
        Hook brokenSecurityHook = new Hook() {
            @Override
            public HookResult execute(HookContext context) {
                throw new IllegalStateException("权限服务不可用");
            }

            @Override
            public HookFailurePolicy failurePolicy() {
                return HookFailurePolicy.FAIL_CLOSED;
            }
        };
        HookRegistry registry = new HookRegistry()
                .register(HookType.PRE_TOOL_USE, "security", brokenSecurityHook)
                .register(HookType.PRE_TOOL_USE, "later", context -> {
                    laterExecuted.set(true);
                    return HookResult.proceed();
                });

        HookResult result = registry.fire(HookContext.forPreTool(null, List.of(), null));

        assertTrue(result.isBlocked());
        assertTrue(result.message().contains("安全策略阻断"));
        assertFalse(laterExecuted.get());
    }

    @Test
    void PermissionHook声明FailClosed() {
        assertEquals(HookFailurePolicy.FAIL_CLOSED,
                new PermissionHook(null).failurePolicy());
    }

    @Test
    void StopHook可返回续跑决策() {
        HookRegistry registry = new HookRegistry()
                .register(HookType.STOP, context -> HookResult.continueLoop("还需验证测试"));

        HookResult result = registry.fire(HookContext.forStop(null, List.of()));

        assertTrue(result.isContinueLoop());
        assertEquals("还需验证测试", result.message());
    }

    @Test
    void 派生Registry继承应用Hook但后续注册彼此隔离() {
        AtomicInteger sharedExecutions = new AtomicInteger();
        HookRegistry application = new HookRegistry()
                .register(HookType.POST_TOOL_USE, context -> {
                    sharedExecutions.incrementAndGet();
                    return HookResult.proceed();
                });

        HookRegistry agent = application.copy()
                .register(HookType.POST_TOOL_USE, context -> HookResult.proceed());

        assertEquals(1, application.count(HookType.POST_TOOL_USE));
        assertEquals(2, agent.count(HookType.POST_TOOL_USE));
        agent.fire(HookContext.forPostTool(null, List.of(), null, null));
        application.fire(HookContext.forPostTool(null, List.of(), null, null));
        assertEquals(2, sharedExecutions.get(), "共享 Hook 实例应覆盖应用和派生执行链");
    }

    @Test
    void registerFirst确保安全Hook先于业务Hook执行() {
        List<String> order = new ArrayList<>();
        HookRegistry registry = new HookRegistry()
                .register(HookType.PRE_TOOL_USE, "business", context -> {
                    order.add("business");
                    return HookResult.proceed();
                })
                .registerFirst(HookType.PRE_TOOL_USE, "security", context -> {
                    order.add("security");
                    return HookResult.proceed();
                });

        registry.fire(HookContext.forPreTool(null, List.of(), null));

        assertEquals(List.of("security", "business"), order);
    }
}
