package com.thoughtcoding.team;

/**
 * 一个存活队友的句柄 —— 供 {@link TeamManager} 注册表与状态展示使用。
 *
 * <p>字段：name（唯一名）、role（角色描述）、thread（后台守护线程）、
 * teammate（循环本体，可 requestStop）、startedAt（启动毫秒）、finished（是否已结束）。
 */
public class TeammateHandle {

    private final String name;
    private final String role;
    private final Thread thread;
    private final Teammate teammate;
    private final long startedAt;
    private volatile boolean finished;

    public TeammateHandle(String name, String role, Thread thread, Teammate teammate) {
        this.name = name;
        this.role = role;
        this.thread = thread;
        this.teammate = teammate;
        this.startedAt = System.currentTimeMillis();
        this.finished = false;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public Thread getThread() {
        return thread;
    }

    public Teammate getTeammate() {
        return teammate;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public boolean isFinished() {
        return finished || !thread.isAlive();
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }
}
