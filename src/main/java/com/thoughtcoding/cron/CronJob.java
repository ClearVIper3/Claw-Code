package com.thoughtcoding.cron;

/**
 * 定时任务(cron)领域模型 —— 一条可到点自动执行的调度项。
 *
 * <p>字段对齐 s14 教学代码：id（如 {@code cron_123456}）、cron（5 段 cron 表达式）、
 * prompt（到点后注入给 Agent 的提示文本）、recurring（是否循环）、durable（是否落盘）。
 * one-shot 触发一次后自动移除；recurring 按表达式循环；durable 持久化到
 * {@code .scheduled_tasks.json}、跨会话恢复，session-only 仅存活于当前进程。
 *
 * <p>采用普通 POJO 而非 record：Jackson 反序列化 + 可变编辑更顺（对齐 Task/SessionService 的 DTO 风格）。
 */
public class CronJob {

    private String id;
    private String cron;
    private String prompt;
    private boolean recurring;
    private boolean durable;

    public CronJob() {
    }

    public CronJob(String id, String cron, String prompt, boolean recurring, boolean durable) {
        this.id = id;
        this.cron = cron;
        this.prompt = prompt;
        this.recurring = recurring;
        this.durable = durable;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public boolean isRecurring() {
        return recurring;
    }

    public void setRecurring(boolean recurring) {
        this.recurring = recurring;
    }

    public boolean isDurable() {
        return durable;
    }

    public void setDurable(boolean durable) {
        this.durable = durable;
    }
}
