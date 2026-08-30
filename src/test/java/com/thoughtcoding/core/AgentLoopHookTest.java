package com.thoughtcoding.core;

import com.thoughtcoding.hook.HookResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopHookTest {

    @Test
    void Stop续跑原因会转为模型可见指令() {
        String prompt = AgentLoop.buildStopContinuationPrompt(
                HookResult.continueLoop("请先运行完整测试"));

        assertTrue(prompt.contains("STOP Hook 请求继续执行"));
        assertTrue(prompt.contains("请先运行完整测试"));
    }

    @Test
    void Stop续跑原因为空时使用默认指令() {
        String prompt = AgentLoop.buildStopContinuationPrompt(
                HookResult.continueLoop("  "));

        assertTrue(prompt.contains("当前任务尚未完成"));
        assertEquals(3, AgentLoop.MAX_STOP_CONTINUATIONS);
    }
}
