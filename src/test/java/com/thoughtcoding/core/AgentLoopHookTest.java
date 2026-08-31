package com.thoughtcoding.core;

import com.thoughtcoding.hook.HookResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopHookTest {

    @Test
    void shouldConvertStopContinuationReasonIntoModelInstruction() {
        String prompt = AgentLoop.buildStopContinuationPrompt(
                HookResult.continueLoop("请先运行完整测试"));

        assertTrue(prompt.contains("STOP Hook 请求继续执行"));
        assertTrue(prompt.contains("请先运行完整测试"));
    }

    @Test
    void shouldUseDefaultInstructionWhenStopContinuationReasonIsBlank() {
        String prompt = AgentLoop.buildStopContinuationPrompt(
                HookResult.continueLoop("  "));

        assertTrue(prompt.contains("当前任务尚未完成"));
        assertEquals(3, AgentLoop.MAX_STOP_CONTINUATIONS);
    }
}
