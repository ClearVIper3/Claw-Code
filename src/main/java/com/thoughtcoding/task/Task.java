package com.thoughtcoding.task;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务(task)领域模型 —— 确定性 CRUD 任务图的一条节点。
 *
 * <p>字段对齐 s12 任务系统：稳定短序列 id（文件名 {@code <id>.json}）、subject/description、
 * status 三态（pending/in_progress/completed）、owner（多 Agent 协作）、blockedBy（依赖 id 列表）。
 * 依赖语义：只有 blockedBy 全部 completed 的任务才可开始（canStart），缺失依赖视为阻塞。
 * s18 新增 {@code worktree}：任务绑定的 git worktree 名（可空）——认领后队友的工具在该副本内执行。
 *
 * <p>采用普通 POJO 而非 record：Jackson 反序列化 + 可变编辑更顺（对齐 SessionService 的 DTO 风格）。
 */
public class Task {

    /** 合法状态值。 */
    public static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    private String id;
    private String subject;
    private String description;
    private String status;
    private String owner;
    /** s18：绑定的 worktree 名（可空，null=未绑定，工具在仓库根执行）。 */
    private String worktree;
    private List<String> blockedBy = new ArrayList<>();

    public Task() {
    }

    public Task(String id, String subject, String description, String status, String owner, List<String> blockedBy) {
        this.id = id;
        this.subject = subject;
        this.description = description;
        this.status = status;
        this.owner = owner;
        this.blockedBy = blockedBy != null ? blockedBy : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getWorktree() {
        return worktree;
    }

    public void setWorktree(String worktree) {
        this.worktree = worktree;
    }

    public List<String> getBlockedBy() {
        return blockedBy;
    }

    public void setBlockedBy(List<String> blockedBy) {
        this.blockedBy = blockedBy != null ? blockedBy : new ArrayList<>();
    }
}
