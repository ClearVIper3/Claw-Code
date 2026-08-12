package com.thoughtcoding.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队友消息总线 —— 文件邮箱 {@code <工作目录>/.mailboxes/<agent>.jsonl}，对齐 s15 教学代码。
 *
 * <p>每个 Agent（含保留收件人 {@link #LEAD}）对应一个 {@code .jsonl} 邮箱：
 * <ul>
 *   <li>{@link #send} —— 向收件人邮箱<b>追加</b>一行（append）；</li>
 *   <li>{@link #readInbox} —— <b>读即销毁</b>：整读邮箱后删除文件（与 s15 的 read + unlink 一致）。</li>
 * </ul>
 *
 * <p>并发安全：本进程内 lead 线程与多个队友线程会同时写/读，故<b>按收件人各持一把锁</b>，
 * 保证「append」与「read+delete」互斥，消除「读后删之间又被 append 的丢消息」竞态。
 * 写盘失败静默降级（对齐各 Store 的容错风格），不阻塞消息推进。
 */
public final class MessageBus {

    /** 保留收件人：主 Agent（lead）。所有队友的最终结果都发到这里。 */
    public static final String LEAD = "lead";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每个收件人一把锁：邮箱 append 与 read+delete 必须在同一把锁内原子完成。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private final Path mailboxDir;

    public MessageBus() {
        this(Paths.get(System.getProperty("user.dir"), ".mailboxes"));
    }

    public MessageBus(Path mailboxDir) {
        this.mailboxDir = mailboxDir;
        try {
            Files.createDirectories(mailboxDir);
        } catch (IOException ignored) {
            // 目录创建失败 → 邮箱退化为空（send 也不抛，静默丢弃）
        }
    }

    /** 发送一条消息到 {@code to} 的邮箱（append 一行）。收件人不存在时自动建邮箱。 */
    public void send(String from, String to, String content, String type) {
        send(new TeamMessage(from, to, content, type));
    }

    public void send(TeamMessage msg) {
        if (msg == null || msg.getTo() == null || msg.getTo().isBlank()) {
            return;
        }
        synchronized (lockFor(msg.getTo())) {
            try {
                Path inbox = mailboxDir.resolve(msg.getTo() + ".jsonl");
                Files.createDirectories(mailboxDir);
                Files.writeString(inbox, MAPPER.writeValueAsString(msg) + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // 写盘失败不抛：消息丢弃，不阻塞队友推进
            }
        }
    }

    /** 读即销毁：整读收件人邮箱，然后删除文件。空/不存在返回空列表。 */
    public List<TeamMessage> readInbox(String agent) {
        if (agent == null || agent.isBlank()) {
            return List.of();
        }
        synchronized (lockFor(agent)) {
            Path inbox = mailboxDir.resolve(agent + ".jsonl");
            if (!Files.exists(inbox)) {
                return List.of();
            }
            List<TeamMessage> out = new ArrayList<>();
            try {
                List<String> lines = Files.readAllLines(inbox, java.nio.charset.StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    try {
                        TeamMessage msg = MAPPER.readValue(line, TeamMessage.class);
                        if (msg != null) {
                            out.add(msg);
                        }
                    } catch (Exception ignored) {
                        // 单条坏行跳过，不阻塞整箱
                    }
                }
                Files.deleteIfExists(inbox);   // 读即销毁（与 s15 的 unlink 一致）
            } catch (IOException ignored) {
                // 读失败 → 视为空箱（下次可再试）
            }
            return out;
        }
    }

    /** 非破坏性检查：收件人邮箱是否存在且非空。 */
    public boolean hasMessages(String agent) {
        if (agent == null || agent.isBlank()) {
            return false;
        }
        Path inbox = mailboxDir.resolve(agent + ".jsonl");
        try {
            return Files.exists(inbox) && Files.size(inbox) > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Object lockFor(String agent) {
        return locks.computeIfAbsent(agent, k -> new Object());
    }
}
