package com.thoughtcoding.team;

import com.thoughtcoding.core.ThoughtCodingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队管理器 —— 持有 {@link MessageBus} 与活动队友注册表，负责 spawn / 跟踪 / 关停。
 *
 * <p>对齐 {@code BackgroundTaskManager} 的守护线程 + {@code ConcurrentHashMap} 风格：
 * 队友线程均为 daemon，JVM 退出不阻塞。
 *
 * <p>需持有 build 后的 {@link ThoughtCodingContext}（队友要复用其 aiService/toolRegistry/
 * contextManager），故仿 {@code SubAgentTool} 的「build 后注册」惯例：先以 null 构造、
 * build 完成后再 {@link #setContext} 绑定。
 */
public final class TeamManager {

    private final MessageBus bus;
    private final ConcurrentHashMap<String, TeammateHandle> teammates = new ConcurrentHashMap<>();
    private final int maxTeammates;
    private final int maxRounds;
    private final int idleTimeoutSeconds;
    private final boolean autoClaim;         // s17 自主模式：idle 期间自动认领任务板就绪任务
    private final long idlePollIntervalMs;   // idle 轮询节拍(ms)

    /** s16 在途协议请求：requestId → 状态机。⚠️ 仅在内存，绝不落盘。 */
    private final ConcurrentHashMap<String, ProtocolState> pendingRequests = new ConcurrentHashMap<>();
    private static final java.util.Random RAND = new java.util.Random();

    private ThoughtCodingContext context;

    public TeamManager(int maxTeammates, int maxRounds, int idleTimeoutSeconds,
                       boolean autoClaim, long idlePollIntervalMs) {
        this.bus = new MessageBus();   // 构造即 ensure .mailboxes/ 存在
        this.maxTeammates = maxTeammates;
        this.maxRounds = maxRounds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.autoClaim = autoClaim;
        this.idlePollIntervalMs = idlePollIntervalMs;
    }

    public void setContext(ThoughtCodingContext context) {
        this.context = context;
    }

    public MessageBus bus() {
        return bus;
    }

    /**
     * 派发一个队友：校验上限 + 名字唯一，在后台守护线程启动。
     * 返回给 lead 的回执文本（spawn_teammate 工具把它作为 tool result 返回）。
     */
    public synchronized String spawn(String name, String role, String prompt) {
        if (context == null) {
            return "团队系统尚未就绪。";
        }
        if (teammates.size() >= maxTeammates) {
            return "已达团队上限(" + maxTeammates + ")，无法再 spawn。";
        }
        String uniq = uniqueName(name);
        Teammate t = new Teammate(context, bus, uniq, role, prompt, maxRounds, idleTimeoutSeconds,
                autoClaim, idlePollIntervalMs);
        Thread th = new Thread(t, "teammate-" + uniq);
        th.setDaemon(true);   // 守护线程，JVM 退出不阻塞（对齐 bg-task-worker）
        TeammateHandle handle = new TeammateHandle(uniq, role, th, t);
        teammates.put(uniq, handle);
        th.start();
        return "已派发 teammate '" + uniq + "' (" + role + ")，它会通过消息总线向你汇报。";
    }

    /** 名字唯一化：重名追加 -2、-3…（reviewer → reviewer-2）。 */
    private String uniqueName(String base) {
        String name = (base == null || base.isBlank()) ? "teammate" : base.trim();
        if (!teammates.containsKey(name)) {
            return name;
        }
        int n = 2;
        while (teammates.containsKey(name + "-" + n)) {
            n++;
        }
        return name + "-" + n;
    }

    /**
     * drain lead 收件箱（读即销毁），并对 s16 协议响应做路由。
     *
     * <p>对齐 s16 {@code consume_lead_inbox(route_protocol=True)}：凡带 {@code request_id} 且类型以
     * {@code _response} 结尾的消息，先经 {@link #matchResponse} 关联回原请求（校验类型 + 幂等防重），
     * 再原样返回给调用方上浮展示。check_inbox 工具与主循环/wake-consumer 都走这里，保证消息不被
     * 未经路由就消费。
     */
    public List<TeamMessage> drainLeadInbox() {
        List<TeamMessage> msgs = bus.readInbox(MessageBus.LEAD);
        for (TeamMessage m : msgs) {
            String type = m.getType();
            if (type != null && type.endsWith("_response") && m.getRequestId() != null) {
                boolean approve = m.getMetadata() != null
                        && Boolean.TRUE.equals(m.getMetadata().get("approve"));
                matchResponse(type, m.getRequestId(), approve);
            }
        }
        return msgs;
    }

    public boolean leadHasMail() {
        return bus.hasMessages(MessageBus.LEAD);
    }

    /** lead 侧发送（send_message 工具走这里）。 */
    public void sendFromLead(String to, String content) {
        bus.send(MessageBus.LEAD, to, content, "message");
    }

    // ────────────────────────────────────────────────────────────────────────
    // s16 请求-响应协议（request_id 关联 + 状态机）
    // ────────────────────────────────────────────────────────────────────────

    /** 生成唯一的协议请求 id（对齐 s16 {@code new_request_id}："req_" + 6 位随机）。 */
    private String newRequestId() {
        String id;
        do {
            id = "req_" + String.format("%06d", RAND.nextInt(1_000_000));
        } while (pendingRequests.containsKey(id));
        return id;
    }

    /** SHUTDOWN 握手（lead 侧）：请求一个队友优雅关闭，登记 pending(shutdown) 并投递 shutdown_request。 */
    public synchronized String requestShutdown(String teammate) {
        if (context == null) {
            return "团队系统尚未就绪。";
        }
        if (teammate == null || teammate.isBlank() || !teammates.containsKey(teammate)) {
            return "找不到队友 '" + teammate + "'，请先用 spawn_teammate 派发。";
        }
        String rid = newRequestId();
        pendingRequests.put(rid, new ProtocolState(
                rid, ProtocolState.KIND_SHUTDOWN, MessageBus.LEAD, teammate, null));
        bus.send(MessageBus.LEAD, teammate, "请求你优雅关闭。",
                ProtocolState.SHUTDOWN_REQUEST, rid, java.util.Map.of());
        return "已向 " + teammate + " 发送关闭请求 (req: " + rid + ")。";
    }

    /** 让队友先交计划（lead 侧）：按 s16 只发一条普通消息，pending 由队友 submit_plan 时创建。 */
    public synchronized String requestPlan(String teammate, String task) {
        if (context == null) {
            return "团队系统尚未就绪。";
        }
        if (teammate == null || teammate.isBlank() || !teammates.containsKey(teammate)) {
            return "找不到队友 '" + teammate + "'，请先用 spawn_teammate 派发。";
        }
        bus.send(MessageBus.LEAD, teammate,
                "请先提交一份计划（调用 submit_plan），任务是：" + (task == null ? "" : task),
                "message");
        return "已请 " + teammate + " 提交计划。";
    }

    /**
     * 提交计划（队友侧，由队友本地拦截 submit_plan 调用）：登记 pending(plan_approval)，
     * 并向 lead 投递 plan_approval_request（带 request_id，lead 据此 review_plan）。
     *
     * <p>不 synchronized：队友线程调用，只碰线程安全结构；返回文本即队友看到的 tool 结果。
     */
    public String submitPlan(String teammateName, String plan) {
        if (context == null) {
            return "团队系统尚未就绪。";
        }
        String rid = newRequestId();
        pendingRequests.put(rid, new ProtocolState(
                rid, ProtocolState.KIND_PLAN, teammateName, MessageBus.LEAD, plan));
        bus.send(teammateName, MessageBus.LEAD, plan == null ? "" : plan,
                ProtocolState.PLAN_REQUEST, rid,
                java.util.Map.of("plan", plan == null ? "" : plan));
        return "计划已提交 (req: " + rid + ")，等待 lead 审批。收到 plan_approval_response 前请勿继续。";
    }

    /** 审批计划（lead 侧）：校验在途 + 终态防重，置 APPROVED/REJECTED 并回投 plan_approval_response。 */
    public synchronized String reviewPlan(String requestId, boolean approve, String feedback) {
        ProtocolState state = pendingRequests.get(requestId);
        if (state == null) {
            return "请求 " + requestId + " 不存在（可能已过期）。";
        }
        if (!ProtocolState.KIND_PLAN.equals(state.getType())) {
            return "请求 " + requestId + " 不是计划审批请求。";
        }
        if (!ProtocolState.PENDING.equals(state.getStatus())) {
            return "请求 " + requestId + " 已处理（" + state.getStatus() + "），忽略重复审批。";
        }
        state.setStatus(approve ? ProtocolState.APPROVED : ProtocolState.REJECTED);
        bus.send(MessageBus.LEAD, state.getSender(),
                approve ? "计划已批准，请按计划执行。"
                        : "计划被驳回，反馈：" + (feedback == null ? "" : feedback),
                ProtocolState.PLAN_RESPONSE, requestId,
                java.util.Map.of("approve", approve, "feedback", feedback == null ? "" : feedback));
        return "计划已" + (approve ? "批准" : "驳回") + " (" + requestId + ")。";
    }

    /**
     * 把响应关联回原请求（对齐 s16 {@code match_response}）：校验响应类型与请求类型匹配，
     * 且请求仍为 pending（重复/过期忽略），否则不流转状态。
     */
    public void matchResponse(String responseType, String requestId, boolean approve) {
        ProtocolState state = pendingRequests.get(requestId);
        if (state == null) {
            return; // 未知/过期请求，忽略
        }
        boolean typeOk = switch (state.getType()) {
            case ProtocolState.KIND_SHUTDOWN -> ProtocolState.SHUTDOWN_RESPONSE.equals(responseType);
            case ProtocolState.KIND_PLAN -> ProtocolState.PLAN_RESPONSE.equals(responseType);
            default -> false;
        };
        if (!typeOk) {
            return; // 响应类型与请求类型不匹配，忽略
        }
        if (!ProtocolState.PENDING.equals(state.getStatus())) {
            return; // 已终态，忽略重复响应
        }
        state.setStatus(approve ? ProtocolState.APPROVED : ProtocolState.REJECTED);
    }

    public List<TeammateHandle> active() {
        return new ArrayList<>(teammates.values());
    }

    /** 关停：请求所有队友停止并中断其线程（守护线程即便漏网也不阻塞 JVM 退出）。 */
    public void shutdown() {
        for (TeammateHandle h : teammates.values()) {
            h.getTeammate().requestStop();
            h.getThread().interrupt();
        }
    }
}
