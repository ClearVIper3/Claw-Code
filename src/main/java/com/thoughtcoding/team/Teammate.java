package com.thoughtcoding.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SubagentTurn;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolCallRef;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.PermissionGate;
import com.thoughtcoding.security.PermissionResult;
import com.thoughtcoding.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


//TODO: 优化CLI表现
/**
 * 队友循环 —— 在【全新、隔离】的对话历史里、后台守护线程上跑一个独立的 agentic 循环。
 *
 * <p>由 {@code SubAgent} 改写而来，是「Agent Teams」的核心 worker。与主 {@code AgentLoop} 的区别：
 * <ul>
 *   <li>自带独立 history（不碰主对话），只通过消息总线向 lead 汇报；</li>
 *   <li>看不到 {@code spawn_teammate} / {@code check_inbox} 工具（已从可见的工具规格中过滤），
 *       因此不可能再派生队友/查看 lead 收件箱（防递归的唯一手段）；</li>
 *   <li>调用 {@link com.thoughtcoding.service.AIService#chatOnceIsolated} —— 隔离的模型往返，
 *       不抢占主循环共享的流式回调/生成状态，因此可以<b>与主循环并发</b>驱动同一个底层模型；</li>
 *   <li><b>静默</b>：tokenSink 传 null、不写终端 —— 后台线程不碰共享 line reader/终端，
 *       避免与主线程的输入提示符交织（老 subAgent 同步执行可流式，后台队友不行）；</li>
 *   <li>直连 {@link PermissionGate#check}：保留 DENY 硬拦截、跳过需要交互确认的 WARN ——
 *       后台线程不能占用 UI 的确认框；</li>
 *   <li>每轮先 drain 自己邮箱（lead/其他队友发来的消息）注入历史；结束时向 lead 发
 *       {@code result} 汇报。</li>
 * </ul>
 *
 * <p><b>有界协议 worker</b>（对齐 s16 教学版）：活跃回合最多跑 {@code maxRounds} 轮；
 * 自然停顿点（无工具调用的回合）进入<b>有限等待</b>——轮询自己邮箱最多 {@code idleTimeoutSeconds}，
 * 收到协议消息/新指令就继续，超时则向 lead 发 {@code result} 汇报并退出。空转等待<b>不</b>占用
 * {@code maxRounds}（只约束活跃 LLM 回合）。等待期间响应 {@code stopRequested}/{@code interrupt}，
 * {@link TeamManager#shutdown} 仍可释放每个队友；daemon 线程不阻塞 JVM 退出。
 * 给已结束的队友再发消息 = 落入其邮箱但不再被读取。</p>
 */
public final class Teammate implements Runnable {

    /** 队友不可见的工具（防递归 + 防越权）：不能派生队友、不能读 lead 收件箱、不能使用 lead 侧协议工具。 */
    public static final Set<String> TEAMMATE_EXCLUDED_TOOLS = Set.of(
            "spawn_teammate", "check_inbox", "request_shutdown", "request_plan", "review_plan");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;
    private final MessageBus bus;
    private final String name;
    private final String role;
    private final String initialPrompt;
    private final int maxRounds;
    private final int idleTimeoutSeconds;

    private volatile boolean stopRequested = false;

    public Teammate(ThoughtCodingContext context, MessageBus bus,
                    String name, String role, String initialPrompt,
                    int maxRounds, int idleTimeoutSeconds) {
        this.context = context;
        this.bus = bus;
        this.name = name;
        this.role = role;
        this.initialPrompt = initialPrompt;
        this.maxRounds = maxRounds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public String getName() {
        return name;
    }

    public void requestStop() {
        this.stopRequested = true;
    }

    @Override
    public void run() {
        // 全新隔离历史：队友看不到主对话，任务信息全在 initialPrompt 里
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("user", initialPrompt));

        String sysPrompt = context.getContextManager().buildTeammateSystemPrompt(name, role);
        ToolDispatcher dispatcher = new ToolDispatcher(context.getToolRegistry());

        String lastText = "";

        // 活跃回合计数：只统计真实的 LLM 回合，有限等待（空转轮询）不计入，避免 maxRounds 被等待消耗
        int activeRounds = 0;
        while (activeRounds < maxRounds) {
            if (stopRequested || Thread.currentThread().isInterrupted()) {
                break;
            }

            // 1) 先收自己的邮箱（lead/其他队友发来的回复），按协议类型分发：
            //    shutdown_request → 自动确认并优雅退出；plan_approval_response → 注入批准/驳回；
            //    其余 → 作为 <team_message> 注入历史
            for (TeamMessage m : bus.readInbox(name)) {
                String mType = m.getType();
                if (ProtocolState.SHUTDOWN_REQUEST.equals(mType)) {
                    // 优雅关机握手：回 shutdown_response(approve=true) + 一条 result，然后结束
                    bus.send(name, MessageBus.LEAD, "同意关闭。",
                            ProtocolState.SHUTDOWN_RESPONSE, m.getRequestId(),
                            java.util.Map.of("approve", true));
                    bus.send(name, MessageBus.LEAD,
                            "队友 " + name + " 收到关闭请求，已优雅退出。", "result");
                    return;
                } else if (ProtocolState.PLAN_RESPONSE.equals(mType)) {
                    boolean approve = m.getMetadata() != null
                            && Boolean.TRUE.equals(m.getMetadata().get("approve"));
                    String feedback = m.getMetadata() == null
                            ? "" : String.valueOf(m.getMetadata().getOrDefault("feedback", ""));
                    history.add(new ChatMessage("user", approve
                            ? "[计划已批准] 请按计划继续执行。"
                            : "[计划被驳回] 反馈：" + feedback + "\n请据此修订后再 submit_plan。"));
                } else {
                    history.add(new ChatMessage("user",
                            "<team_message from=\"" + safe(m.getFrom()) + "\" type=\""
                                    + safe(mType) + "\">\n" + safe(m.getContent())
                                    + "\n</team_message>"));
                }
            }

            // 2) 一次隔离往返，静默（tokenSink=null），排除防递归/lead 侧工具
            SubagentTurn turn;
            try {
                turn = context.getAiService().chatOnceIsolated(
                        sysPrompt, history, null, TEAMMATE_EXCLUDED_TOOLS);
            } catch (Exception e) {
                // chatOnceIsolated 约定永不抛出，这里兜底一次防御
                bus.send(name, MessageBus.LEAD,
                        "队友 " + name + " 调用模型失败: " + e.getMessage(), "error");
                return;
            }
            activeRounds++;
            lastText = turn.getText();

            // 3) 无工具调用 → 有限等待：空转轮询自己邮箱最多 idleTimeoutSeconds，
            //    收到新消息就回到循环顶重新分发+新一轮（不占 activeRounds）；超时则汇报并结束。
            //    shutdown 请求期间：每轮检查 stopRequested/interrupt，sleep 被中断即退出。
            if (!turn.hasToolCalls()) {
                long deadline = System.currentTimeMillis() + idleTimeoutSeconds * 1000L;
                boolean resumed = false;
                while (System.currentTimeMillis() < deadline) {
                    if (stopRequested || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (bus.hasMessages(name)) {
                        resumed = true;
                        break;
                    }
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (resumed) {
                    continue; // 回到循环顶重新 drain+dispatch+新一轮
                }
                if (!stopRequested) {
                    bus.send(name, MessageBus.LEAD,
                            (lastText == null || lastText.isBlank())
                                    ? "队友 " + name + " 结束，无文本结论。"
                                    : lastText,
                            "result");
                }
                return;
            }

            // 记录携带工具调用的 assistant 消息（供下一轮重建 AiMessage.toolExecutionRequests）
            history.add(ChatMessage.assistantWithToolCalls(turn.getText(), turn.getToolCalls()));

            // 4) 逐个执行 —— 关键不变量：每个工具调用必须严格配对恰好一个 tool 结果，
            //    否则队友绕过了主链路的 sanitizeToolPairs，下一轮会 400。
            for (ToolCallRef ref : turn.getToolCalls()) {
                String id = ref.getId();
                String name = ref.getName();
                Map<String, Object> params = parseArgs(ref.getArguments());

                // 本地拦截 submit_plan：不过 ToolDispatcher，直接经 TeamManager 登记 pending 并投递给 lead
                // （对齐 send_message 双路：全局注册进队友规格、本地拦截保证 sender 身份正确）。
                // 必须补一条配对的 tool 结果，否则下一轮 400。
                if ("submit_plan".equals(name)) {
                    String plan = strParam(params.get("plan"));
                    String receipt = context.getTeamManager().submitPlan(
                            this.name, plan == null ? "" : plan);
                    history.add(ChatMessage.toolResult(id, name, receipt));
                    continue;
                }

                // 本地拦截 send_message：不过 ToolDispatcher，直接投递到总线（队友的通信手段）
                if ("send_message".equals(name)) {
                    String to = strParam(params.get("to"));
                    if (to == null || to.isBlank()) {
                        to = MessageBus.LEAD;
                    }
                    bus.send(this.name, to, strParam(params.get("content")), "message");
                    history.add(ChatMessage.toolResult(id, name,
                            "已投递给 " + to));
                    continue;
                }

                ToolCall call = new ToolCall(name, params, null, false, 0, false, id);

                // 静默硬拦截：只挡 DENY（后台线程无交互确认，WARN 直接放行）
                PermissionResult perm = PermissionGate.check(name, params);
                if (perm != null && perm.type() == PermissionResult.Type.DENY) {
                    String msg = perm.message() != null ? perm.message() : "工具执行被阻止。";
                    history.add(ChatMessage.toolResult(id, name, "已阻止 —— " + msg));
                    continue;
                }

                // 执行 —— 任何异常都转成配对的 tool 结果，绝不逃逸破坏配对
                try {
                    ToolResult result = dispatcher.dispatch(call);
                    String resultText = result.isSuccess()
                            ? (result.getOutput() == null || result.getOutput().isBlank()
                                ? "执行成功（无输出）。" : result.getOutput())
                            : ("执行失败: " + result.getError());
                    history.add(ChatMessage.toolResult(id, name, resultText));
                } catch (Exception e) {
                    history.add(ChatMessage.toolResult(id, name, "执行异常: " + e.getMessage()));
                }
            }
        }

        // 达到最大轮次 / 被停止：也必须给 lead 一条 result，避免 lead 空等
        if (!stopRequested) {
            bus.send(name, MessageBus.LEAD,
                    "队友 " + name + " 已达最大轮次(" + maxRounds + ")，未产出最终结论。", "result");
        }
    }

    /** 解析工具参数 JSON 为 Map；失败则退化为 {"input": 原始串}（与主服务一致的兜底）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("input", argumentsJson);
            return fallback;
        }
    }

    private String strParam(Object v) {
        return v == null ? null : v.toString();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
