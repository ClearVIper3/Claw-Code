package com.thoughtcoding.team;

/**
 * 队友消息领域模型 —— 消息总线上的一条消息。
 *
 * <p>字段对齐 s15 教学代码：from（发送方）、to（接收方）、content（正文）、type（消息类型）、
 * ts（发送时间戳，构造时自动取当前毫秒）。
 *
 * <p>{@code type} 取值：
 * <ul>
 *   <li>{@code result} —— 队友的最终汇报（读到它 = 队友跑完，应唤醒主 Agent）；</li>
 *   <li>{@code progress} —— 队友的中间进度（可选）；</li>
 *   <li>{@code message} —— 自由消息（lead → 队友 或 队友 → lead）；</li>
 *   <li>{@code error} —— 队友出错汇报。</li>
 * </ul>
 *
 * <p>采用普通 POJO 而非 record：Jackson 反序列化一行 {@code .jsonl} 更顺（对齐 Task/CronJob 的 DTO 风格）。
 */
public class TeamMessage {

    private String from;
    private String to;
    private String content;
    private String type;
    private long ts;

    public TeamMessage() {
    }

    public TeamMessage(String from, String to, String content, String type) {
        this.from = from;
        this.to = to;
        this.content = content;
        this.type = type;
        this.ts = System.currentTimeMillis();
    }

    /** 渲染成一行可读文本（用于终端状态 / 系统提醒）。 */
    public String render() {
        return "[" + type + "] " + from + " → " + to + ": " + content;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getTs() {
        return ts;
    }

    public void setTs(long ts) {
        this.ts = ts;
    }
}
