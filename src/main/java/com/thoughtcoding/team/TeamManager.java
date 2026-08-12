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

    private ThoughtCodingContext context;

    public TeamManager(int maxTeammates, int maxRounds) {
        this.bus = new MessageBus();   // 构造即 ensure .mailboxes/ 存在
        this.maxTeammates = maxTeammates;
        this.maxRounds = maxRounds;
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
        Teammate t = new Teammate(context, bus, uniq, role, prompt, maxRounds);
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

    /** drain lead 收件箱（读即销毁）。 */
    public List<TeamMessage> drainLeadInbox() {
        return bus.readInbox(MessageBus.LEAD);
    }

    public boolean leadHasMail() {
        return bus.hasMessages(MessageBus.LEAD);
    }

    /** lead 侧发送（send_message 工具走这里）。 */
    public void sendFromLead(String to, String content) {
        bus.send(MessageBus.LEAD, to, content, "message");
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
