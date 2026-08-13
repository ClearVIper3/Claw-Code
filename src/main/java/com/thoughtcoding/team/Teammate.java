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
import com.thoughtcoding.task.Task;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.tool.ToolDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * 自主队友循环（s17 Autonomous Agents）—— 在【全新、隔离】的对话历史里、后台守护线程上跑一个
 * 独立的 agentic 循环，并具备 WORK → IDLE → SHUTDOWN 生命周期 + 空闲自动认领任务的能力。
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
 *   <li>每个 WORK 阶段先 drain 自己邮箱（lead/其他队友发来的消息/协议）注入历史；结束时向 lead 发
 *       {@code result} 汇报。</li>
 * </ul>
 *
 * <p><b>自主生命周期（s17）：</b>
 * <pre>
 *   WORK : drain邮箱+协议 → chatOnceIsolated → (有工具调用? 执行并继续 : → IDLE)
 *          每个 WORK 阶段独立计 maxRounds（活跃 LLM 回合上限），达到上限即转 IDLE。
 *   IDLE : 以 idlePollIntervalMs 节拍轮询（最多 idleTimeoutSeconds）：
 *            收到邮箱消息 → WORK；任务板上有就绪任务（pending+无owner+依赖完成）→ 原子认领后
 *            注入 {@code <auto-claimed>} 并回 WORK；邮箱空且任务板干涸且超时 → TIMEOUT（自然结束）。
 *   SHUTDOWN: 收到 shutdown_request 自动确认回 shutdown_response+result 后退出；或 stopRequested/interrupt。
 * </pre>
 * 仅当<b>任务板干涸且空闲超时</b>、或收到 shutdown 时才真正退出；只要任务板还有活就会一直干下去
 * （对齐 s17，不设整个生命周期的硬性 round 上限）。等待期间响应 {@code stopRequested}/{@code interrupt}，
 * {@link TeamManager#shutdown} 仍可释放每个队友；daemon 线程不阻塞 JVM 退出。
 * 给已结束的队友再发消息 = 落入其邮箱但不再被读取。</p>
 *
 * <p><b>历史增长控制：</b>{@code chatOnceIsolated} 不裁剪/压缩传入的 history（生命周期原假定短），
 * 但自主队友会跨多任务长期存活 → 在每个 WORK 阶段边界（无悬挂工具配对的安全切点）裁剪历史：
 * 保留首条(identity+任务) + 末尾 {@link #HISTORY_KEEP_TAIL} 条，对齐 s17 的 {@code messages[-20:]}；
 * 裁剪后重注入 identity，保证队友身份/角色在场。</p>
 */
public final class Teammate implements Runnable {

    /** 队友不可见的工具（防递归 + 防越权）：不能派生队友、不能读 lead 收件箱、不能使用 lead 侧协议工具。 */
    public static final Set<String> TEAMMATE_EXCLUDED_TOOLS = Set.of(
            "spawn_teammate", "check_inbox", "request_shutdown", "request_plan", "review_plan");

    /** WORK 阶段边界历史裁剪：保留首条 + 末尾 K 条（对齐 s17 messages[-20:]）。 */
    private static final int HISTORY_KEEP_TAIL = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;
    private final MessageBus bus;
    private final String name;
    private final String role;
    private final String initialPrompt;
    private final int maxRounds;
    private final int idleTimeoutSeconds;
    private final boolean autoClaim;
    private final long idlePollIntervalMs;

    /** WORK/IDLE 两个私有方法共享的可变状态（本队友线程独占，无需同步）。 */
    private List<ChatMessage> history;
    private ToolDispatcher dispatcher;
    private String sysPrompt;
    private String lastText = "";
    /** 共享任务存储（可 null = 任务系统关闭，此时 auto-claim 为空操作）。 */
    private TaskStore taskStore;

    private volatile boolean stopRequested = false;

    public Teammate(ThoughtCodingContext context, MessageBus bus,
                    String name, String role, String initialPrompt,
                    int maxRounds, int idleTimeoutSeconds,
                    boolean autoClaim, long idlePollIntervalMs) {
        this.context = context;
        this.bus = bus;
        this.name = name;
        this.role = role;
        this.initialPrompt = initialPrompt;
        this.maxRounds = maxRounds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.autoClaim = autoClaim;
        this.idlePollIntervalMs = idlePollIntervalMs <= 0 ? 1000 : idlePollIntervalMs;
    }

    public String getName() {
        return name;
    }

    public void requestStop() {
        this.stopRequested = true;
    }

    // ── 生命周期信号 ──

    /** WORK 阶段结果：SHUTDOWN = 收到关机/被中断/模型致命错误；IDLE = 自然停顿或达到本阶段回合上限。 */
    private enum WorkResult { SHUTDOWN, IDLE }

    /** IDLE 阶段结果：WORK = 有活干（邮箱消息或认领到任务）；SHUTDOWN = 被中断；TIMEOUT = 干涸超时。 */
    private enum IdleResult { WORK, SHUTDOWN, TIMEOUT }

    @Override
    public void run() {
        // 全新隔离历史：队友看不到主对话，任务信息全在 initialPrompt 里；首条附带 identity
        history = new ArrayList<>();
        history.add(new ChatMessage("user", identityBlock() + "\n\n" + initialPrompt));

        sysPrompt = context.getContextManager().buildTeammateSystemPrompt(name, role);
        dispatcher = new ToolDispatcher(context.getToolRegistry());
        taskStore = context.getContextManager() != null
                ? context.getContextManager().getTaskStore() : null;

        boolean firstPhase = true;
        while (true) {
            if (stopRequested || Thread.currentThread().isInterrupted()) {
                return; // 外部中断：静默退出，不回 result
            }
            // 新 WORK 阶段（除首轮外）：先裁剪历史（安全切点），再重注入 identity
            if (!firstPhase) {
                trimHistoryAtPhaseBoundary();
                history.add(new ChatMessage("user", identityBlock()));
            }
            firstPhase = false;

            WorkResult wr = runWorkPhase();
            if (wr == WorkResult.SHUTDOWN) {
                return; // shutdown 分支内部已发 result
            }

            IdleResult ir = idlePoll();
            if (ir == IdleResult.WORK) {
                continue; // 有邮箱消息 / 认领到任务 → 回 WORK
            }
            if (ir == IdleResult.SHUTDOWN) {
                return; // stop/interrupt：静默退出
            }
            // TIMEOUT：任务板干涸且空闲超时 —— 唯一自然结束出口
            if (!stopRequested) {
                bus.send(name, MessageBus.LEAD,
                        (lastText == null || lastText.isBlank())
                                ? "队友 " + name + " 空闲超时且无更多可认领任务，已退出。"
                                : lastText,
                        "result");
            }
            return;
        }
    }

    /**
     * 一个 WORK 阶段：活跃 LLM 回合最多 maxRounds（每次调用从 0 重置，s17 语义）。
     * 无工具调用的自然停顿点 → {@code IDLE}（交给 idlePoll 再找活）；shutdown/中断/模型致命错误 → {@code SHUTDOWN}。
     * 关键不变量：每个工具调用必须严格配对恰好一个 tool 结果，否则下一轮隔离往返会 400。
     */
    private WorkResult runWorkPhase() {
        int activeRounds = 0;
        while (activeRounds < maxRounds) {
            if (stopRequested || Thread.currentThread().isInterrupted()) {
                return WorkResult.SHUTDOWN;
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
                    return WorkResult.SHUTDOWN;
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
                return WorkResult.SHUTDOWN;
            }
            activeRounds++;
            lastText = turn.getText();

            // 3) 无工具调用 = 自然停顿点 → 交给 IDLE（不在 WORK 内 sleep；认领到活/收到消息会再回来）
            if (!turn.hasToolCalls()) {
                return WorkResult.IDLE;
            }

            // 记录携带工具调用的 assistant 消息（供下一轮重建 AiMessage.toolExecutionRequests）
            history.add(ChatMessage.assistantWithToolCalls(turn.getText(), turn.getToolCalls()));

            // 4) 逐个执行 —— 关键不变量：每个工具调用必须严格配对恰好一个 tool 结果，
            //    否则队友绕过了主链路的 sanitizeToolPairs，下一轮会 400。
            for (ToolCallRef ref : turn.getToolCalls()) {
                String id = ref.getId();
                String toolName = ref.getName();
                Map<String, Object> params = parseArgs(ref.getArguments());

                // 本地拦截 submit_plan：不过 ToolDispatcher，直接经 TeamManager 登记 pending 并投递给 lead
                // （对齐 send_message 双路：全局注册进队友规格、本地拦截保证 sender 身份正确）。
                // 必须补一条配对的 tool 结果，否则下一轮 400。
                if ("submit_plan".equals(toolName)) {
                    String plan = strParam(params.get("plan"));
                    String receipt = context.getTeamManager().submitPlan(
                            this.name, plan == null ? "" : plan);
                    history.add(ChatMessage.toolResult(id, toolName, receipt));
                    continue;
                }

                // 本地拦截 send_message：不过 ToolDispatcher，直接投递到总线（队友的通信手段）
                if ("send_message".equals(toolName)) {
                    String to = strParam(params.get("to"));
                    if (to == null || to.isBlank()) {
                        to = MessageBus.LEAD;
                    }
                    bus.send(this.name, to, strParam(params.get("content")), "message");
                    history.add(ChatMessage.toolResult(id, toolName,
                            "已投递给 " + to));
                    continue;
                }

                ToolCall call = new ToolCall(toolName, params, null, false, 0, false, id);

                // 静默硬拦截：只挡 DENY（后台线程无交互确认，WARN 直接放行）
                PermissionResult perm = PermissionGate.check(toolName, params);
                if (perm != null && perm.type() == PermissionResult.Type.DENY) {
                    String msg = perm.message() != null ? perm.message() : "工具执行被阻止。";
                    history.add(ChatMessage.toolResult(id, toolName, "已阻止 —— " + msg));
                    continue;
                }

                // 执行 —— 任何异常都转成配对的 tool 结果，绝不逃逸破坏配对
                try {
                    ToolResult result = dispatcher.dispatch(call);
                    String resultText = result.isSuccess()
                            ? (result.getOutput() == null || result.getOutput().isBlank()
                                ? "执行成功（无输出）。" : result.getOutput())
                            : ("执行失败: " + result.getError());
                    history.add(ChatMessage.toolResult(id, toolName, resultText));
                } catch (Exception e) {
                    history.add(ChatMessage.toolResult(id, toolName, "执行异常: " + e.getMessage()));
                }
            }
        }
        // 达到本 WORK 阶段的回合上限：当作自然停顿转 IDLE（不发 result；认领到活/收到消息会继续）
        return WorkResult.IDLE;
    }

    /**
     * IDLE 阶段：以 {@code idlePollIntervalMs} 节拍轮询（最多 idleTimeoutSeconds）——
     * 收到自己邮箱消息 → WORK（drain+协议分发在下一个 WORK 顶完成）；
     * autoClaim 开启且任务板有就绪任务 → 原子认领、注入 {@code <auto-claimed>} 后 → WORK；
     * 邮箱空且任务板干涸且超时 → TIMEOUT（自然结束）。期间响应 stopRequested/interrupt。
     */
    private IdleResult idlePoll() {
        long deadline = System.currentTimeMillis() + idleTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested || Thread.currentThread().isInterrupted()) {
                return IdleResult.SHUTDOWN;
            }
            // 邮箱优先：drain + shutdown 处理在恢复的 WORK 顶完成
            if (bus.hasMessages(name)) {
                return IdleResult.WORK;
            }
            // 任务板：原子认领一个就绪任务（s17 auto-claim）
            if (autoClaim && taskStore != null) {
                Task claimed = taskStore.scanAndClaimOne(name);
                if (claimed != null) {
                    history.add(new ChatMessage("user",
                            "<auto-claimed>Task #" + claimed.getId() + " " + safe(claimed.getSubject())
                                    + "\n" + safe(claimed.getDescription()) + "</auto-claimed>"));
                    observe("auto-claimed task #" + claimed.getId() + " " + safe(claimed.getSubject()));
                    return IdleResult.WORK;
                }
            }
            try {
                Thread.sleep(idlePollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return IdleResult.SHUTDOWN;
            }
        }
        return IdleResult.TIMEOUT;
    }

    /**
     * 仅在 WORK 阶段边界调用：保留首条(identity+任务) + 末尾 {@link #HISTORY_KEEP_TAIL} 条。
     * 此处为安全切点——runWorkPhase 只在「无工具调用」回合返回 IDLE，所有工具调用都已配对了结果，
     * 切断不会破坏 tool_use/tool_result 配对；再防御性回退，确保窗口首条不是孤立的 tool 结果。
     */
    private void trimHistoryAtPhaseBoundary() {
        if (history.size() <= HISTORY_KEEP_TAIL + 1) {
            return;
        }
        int start = history.size() - HISTORY_KEEP_TAIL;
        while (start > 1 && history.get(start).isToolMessage()) {
            start--; // 防御：窗口首条不为孤立 tool 结果
        }
        List<ChatMessage> trimmed = new ArrayList<>();
        trimmed.add(history.get(0));                                     // 首条：原始 identity + initialPrompt
        trimmed.addAll(history.subList(start, history.size()));
        history.clear();
        history.addAll(trimmed);
    }

    /** 身份块：首轮拼到任务前、每个新 WORK 阶段重注入（裁剪后原首条可能已远）。 */
    private String identityBlock() {
        return "<identity>你是团队成员 " + safe(name) + "，角色：" + safe(role)
                + "。自主工作：完成手头任务后，如任务板还有依赖已满足的待认领任务，系统会自动分配下一个"
                + "（以 <auto-claimed> 注入）；完成它并调用 task_complete。任务板干涸且空闲超时后你会自动退出。</identity>";
    }

    /** 可观测：仅向 lead 发一条 progress（队友静默守护线程，绝不写共享终端/line reader）。 */
    private void observe(String detail) {
        bus.send(name, MessageBus.LEAD, "[autonomous] " + detail, "progress");
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
