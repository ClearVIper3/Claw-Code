package com.thoughtcoding.core;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取消时工具配对不变量的单元测试。
 *
 * <p>铁律：assistant 消息里的每个工具调用，必须有恰好一条同 id 的 tool 结果，
 * 否则发给模型会 400。取消路径（fillCancelledResults）绝不能破坏它。
 */
class AgentLoopCancelTest {

    private static ToolCall call(String toolName, String id) {
        return new ToolCall(toolName, new HashMap<>(), null, false, 0, false, id);
    }

    /** 模拟完整历史：一条带工具调用的 assistant 消息 + 若干 tool 结果。 */
    private static List<ChatMessage> historyWithAssistant(ToolCall... calls) {
        List<ChatMessage> history = new ArrayList<>();
        List<com.thoughtcoding.model.ToolCallRef> refs = new ArrayList<>();
        for (ToolCall c : calls) {
            refs.add(new com.thoughtcoding.model.ToolCallRef(c.getProviderCallId(),
                    c.getToolName(), "{}"));
        }
        history.add(ChatMessage.assistantWithToolCalls("", refs));
        return history;
    }

    /** 配对不变量：每个调用 id 恰好对应一条 tool 结果，且无孤立结果。 */
    private static void assertStrictlyPaired(List<ChatMessage> history) {
        Map<String, Integer> resultCount = new HashMap<>();
        for (ChatMessage m : history) {
            if (m.isToolMessage() && m.getToolCallId() != null) {
                resultCount.merge(m.getToolCallId(), 1, Integer::sum);
            }
        }
        // 每个调用 id 恰好一条结果
        for (ChatMessage m : history) {
            if (m.getToolCalls() != null) {
                for (com.thoughtcoding.model.ToolCallRef ref : m.getToolCalls()) {
                    assertEquals(1, resultCount.getOrDefault(ref.getId(), 0),
                            "调用 " + ref.getId() + " 必须有恰好一条配对结果");
                }
            }
        }
        // 无孤立结果（每个结果都有对应调用）
        Map<String, Integer> callCount = new HashMap<>();
        for (ChatMessage m : history) {
            if (m.getToolCalls() != null) {
                for (com.thoughtcoding.model.ToolCallRef ref : m.getToolCalls()) {
                    callCount.merge(ref.getId(), 1, Integer::sum);
                }
            }
        }
        for (String id : resultCount.keySet()) {
            assertEquals(1, callCount.getOrDefault(id, 0),
                    "结果 " + id + " 必须有对应的调用，不能是孤立结果");
        }
    }

    @Test
    void shouldAppendPairedResultsForRemainingCallsWhenCancelledMidBatch() {
        ToolCall c1 = call("bash", "id-1");
        ToolCall c2 = call("read", "id-2");
        ToolCall c3 = call("subAgent", "id-3");
        List<ChatMessage> history = historyWithAssistant(c1, c2, c3);

        // c1 正常执行完成
        history.add(ChatMessage.toolResult("id-1", "bash", "ok"));
        // 从 c2 开始被取消
        AgentLoop.fillCancelledResults(List.of(c1, c2, c3), 1, history);

        assertEquals(3, history.stream().filter(ChatMessage::isToolMessage).count(),
                "3 个调用应有 3 条 tool 结果");
        assertStrictlyPaired(history);
    }

    @Test
    void shouldAppendPairedResultsForAllCallsWhenCancelledBeforeBatch() {
        ToolCall c1 = call("bash", "id-1");
        ToolCall c2 = call("read", "id-2");
        List<ChatMessage> history = historyWithAssistant(c1, c2);

        AgentLoop.fillCancelledResults(List.of(c1, c2), 0, history);

        assertStrictlyPaired(history);
        // 补的应该是「用户已取消」占位文本
        for (ChatMessage m : history) {
            if (m.isToolMessage()) {
                assertTrue(m.getContent().contains("用户已取消"),
                        "取消补位应包含「用户已取消」说明");
            }
        }
    }

    @Test
    void shouldHandleEmptyBatchAndOutOfRangeStartIndex() {
        List<ChatMessage> history = historyWithAssistant();
        assertDoesNotThrow(() -> AgentLoop.fillCancelledResults(List.of(), 0, history));
        assertDoesNotThrow(() -> AgentLoop.fillCancelledResults(List.of(call("bash", "x")), 5, history));
        assertEquals(0, history.stream().filter(ChatMessage::isToolMessage).count());
    }
}
