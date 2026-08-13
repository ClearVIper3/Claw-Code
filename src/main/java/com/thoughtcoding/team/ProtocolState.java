package com.thoughtcoding.team;

/**
 * s16 协议状态机 —— 一条在途的请求-响应协议记录（pending → approved | rejected）。
 *
 * <p>与 s16 教学代码的 {@code ProtocolState(request_id, type, sender, target, status, payload, created_at)}
 * 对齐：{@link TeamManager} 用 {@code ConcurrentHashMap<requestId, ProtocolState>} 持有<b>在途</b>请求，
 * 收到匹配的 {@code *_response} 后经 {@link TeamManager#matchResponse} 流转终态。
 *
 * <p>type 与 status 采用 {@code String} 常量而非 enum：协议消息类型经由 {@link TeamMessage#type}
 * （本就是自由 String）在总线上传输，用 String 免去每次收发的 enum⇄String 映射，且混合新旧
 * {@code .jsonl} 天然可解析（对齐 {@link MessageBus#LEAD} 这种集中常量的既有风格）。
 *
 * <p>⚠️ <b>仅在内存</b>：pending 请求是会话级在途状态，<b>绝不落盘</b>——落盘会重演
 * 「cron 删除后仍存在于内存 / 过期任务文件遗留」那类 bug。
 */
public final class ProtocolState {

    // 总线消息类型（TeamMessage.type 取值）
    public static final String SHUTDOWN_REQUEST  = "shutdown_request";
    public static final String SHUTDOWN_RESPONSE = "shutdown_response";
    public static final String PLAN_REQUEST      = "plan_approval_request";
    public static final String PLAN_RESPONSE     = "plan_approval_response";

    // 协议种类（matchResponse 用它做响应类型↔请求类型校验）
    public static final String KIND_SHUTDOWN = "shutdown";
    public static final String KIND_PLAN     = "plan_approval";

    // 状态
    public static final String PENDING  = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    private final String requestId;
    private final String type;      // 协议种类（KIND_*）
    private final String sender;
    private final String target;
    private volatile String status; // PENDING → APPROVED | REJECTED
    private final Object payload;   // shutdown: null；plan: 计划文本
    private final long createdAt;

    public ProtocolState(String requestId, String type, String sender, String target, Object payload) {
        this.requestId = requestId;
        this.type = type;
        this.sender = sender;
        this.target = target;
        this.payload = payload;
        this.status = PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getType() {
        return type;
    }

    public String getSender() {
        return sender;
    }

    public String getTarget() {
        return target;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Object getPayload() {
        return payload;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
