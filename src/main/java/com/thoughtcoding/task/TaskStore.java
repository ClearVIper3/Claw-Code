package com.thoughtcoding.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thoughtcoding.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务(task)存储层 —— 确定性 CRUD，仿 {@code MemoryStore} 的文件持久化，但用 JSON。
 *
 * <p>目录结构（相对工作目录，与 {@code .memory/} 平行）：
 * <pre>
 *   .tasks/
 *     .seq              ← 单调递增的 id 计数文件（隐藏，非任务文件；防止删除后 id 复用）
 *     1.json            ← 每个任务一个文件，{@code <id>.json}，Jackson 序列化（INDENT_OUTPUT 便于人读）
 *     2.json
 *     ...
 * </pre>
 *
 * <p>{@code id} 为短序列（1, 2, 3…），由 {@code .seq} 驱动：create 时读 +1 写回。
 * 与 memory 不同，本类<b>不做任何 LLM 调用</b>——纯确定性 CRUD，无需 recall/remember/dream 那层。
 * 依赖语义（s12）：{@code canStart} 要求 blockedBy 全部 completed；缺失依赖视为阻塞。
 * 任何文件操作失败都降级、不抛（对齐 MemoryStore 的容错风格）。
 *
 * <p><b>并发（s17 自主队友）：</b>Lead 与多个后台队友线程共享同一实例，写方法/复合读一律
 * {@code synchronized}，单一 monitor 既保证 find-then-claim 原子（{@link #scanAndClaimOne}），
 * 又保留 {@code LinkedHashMap} 的插入序（"写入序即展示序"）。队友约 1s 轮询一次，竞争可忽略。
 */
public final class TaskStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** id 计数文件（隐藏，不匹配 {@code *.json} glob，天然不参与任务加载）。 */
    private static final String SEQ_FILE = ".seq";

    /** 单个 id 是否为纯数字（id 文件名）。 */
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d+");

    private final Path dir;
    private final Map<String, Task> tasks; // id → Task，写入序即展示序

    private TaskStore(Path dir, Map<String, Task> tasks) {
        this.dir = dir;
        this.tasks = tasks;
    }

    /**
     * 加载（或创建）任务目录。目录不存在则 {@code createDirectories} 后视为空库。
     * 解析/列举失败不阻塞——降级为空库。返回的实例可直接用 CRUD 方法修改。
     */
    public static TaskStore load(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 目录创建失败 → 降级为空库（后续写操作也会各自失败降级）
        }
        Map<String, Task> map = new LinkedHashMap<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
                for (Path file : files) {
                    String filename = file.getFileName().toString();
                    if (!filename.endsWith(".json")) {
                        continue; // 跳过 .seq 等非任务文件
                    }
                    try {
                        String raw = Files.readString(file);
                        Task t = MAPPER.readValue(raw, Task.class);
                        map.put(t.getId(), t);
                    } catch (Exception ignored) {
                        // 单条任务解析失败不阻塞其余任务加载
                    }
                }
            } catch (IOException ignored) {
                // 目录列举失败 → 降级为空库
            }
        }
        return new TaskStore(dir, map);
    }

    // ── 生命周期：create / get / list / update / delete ──

    /**
     * 创建一个新任务：分配下一个短序列 id，status=pending，owner=null，落盘后返回。
     * blockedBy 中缺失的依赖不校验——依赖校验只发生在 claim 时。
     */
    public synchronized Task create(String subject, String description, List<String> blockedBy) {
        Task t = new Task(nextId(), subject, description, "pending", null, blockedBy);
        persist(t);
        tasks.put(t.getId(), t);
        return t;
    }

    public synchronized Task get(String id) {
        return tasks.get(id);
    }

    public synchronized List<Task> list() {
        return List.copyOf(tasks.values());
    }

    /**
     * 编辑任务字段/依赖边/状态。返回编辑后的任务；id 不存在返回 null。
     *
     * <p>status=deleted → 删除任务；addBlockedBy 追加依赖边；addBlocks 给目标任务反向加边
     * （把本任务 id 追加到目标任务的 blockedBy）。其余可选字段非 null 才更新。
     */
    public synchronized Task update(String id, String subject, String description, String owner,
                       List<String> addBlockedBy, List<String> addBlocks, String status) {
        Task t = tasks.get(id);
        if (t == null) {
            return null;
        }
        if ("deleted".equals(status)) {
            delete(id);
            return null;
        }
        if (subject != null) {
            t.setSubject(subject);
        }
        if (description != null) {
            t.setDescription(description);
        }
        if (owner != null) {
            t.setOwner(owner);
        }
        if (status != null && Task.STATUSES.contains(status)) {
            t.setStatus(status);
        }
        if (addBlockedBy != null && !addBlockedBy.isEmpty()) {
            for (String dep : addBlockedBy) {
                if (!t.getBlockedBy().contains(dep)) {
                    t.getBlockedBy().add(dep);
                }
            }
        }
        if (addBlocks != null && !addBlocks.isEmpty()) {
            for (String targetId : addBlocks) {
                Task target = tasks.get(targetId);
                if (target != null && !target.getBlockedBy().contains(id)) {
                    target.getBlockedBy().add(id);
                    persist(target);
                }
            }
        }
        persist(t);
        return t;
    }

    public synchronized boolean delete(String id) {
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (IOException ignored) {
            // 磁盘删除失败不阻塞内存移除（下次 load 会重新读到旧文件，属可接受降级）
        }
        return tasks.remove(id) != null;
    }

    /**
     * 是否整图已完成：非空且全部任务 status 都是 completed。
     * 空库返回 false（避免对空图误触发清空）。
     */
    public synchronized boolean allCompleted() {
        if (tasks.isEmpty()) {
            return false;
        }
        for (Task t : tasks.values()) {
            if (!"completed".equals(t.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清空任务图：删掉 .tasks/ 下所有常规文件（含 .seq，计数一并复位）并清空内存。
     * 仅在 {@link #allCompleted()} 为真时调用——此时没有任何任务还在等依赖，删文件不会
     * 让下游 {@link #canStart} 因"依赖缺失"而永久阻塞。任一删除失败静默忽略（内存已清即可用），
     * 对齐本类"文件操作失败都降级、不抛"的容错风格。
     */
    public synchronized void clearAll() {
        try (var stream = Files.list(dir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (Exception ignored) {
            // 目录列举/删除失败不影响内存清空（下次 load 会重新读到残留文件，属可接受降级）
        }
        tasks.clear();
    }

    // ── 生命周期守卫：claim / complete / canStart / unlockedBy ──

    /**
     * 认领任务：仅 pending 且 {@link #canStart}（依赖全完成）才可转为 in_progress 并设 owner。
     * 被阻塞或状态非 pending 返回 null（调用方转错误文本）。
     */
    public synchronized Task claim(String id, String owner) {
        Task t = tasks.get(id);
        if (t == null || !"pending".equals(t.getStatus()) || !canStart(id)) {
            return null;
        }
        t.setOwner(owner);
        t.setStatus("in_progress");
        persist(t);
        return t;
    }

    /**
     * 完成任务：仅 in_progress 可转 completed。返回完成的任务；否则 null。
     * 异常状态修正请走 {@link #update} 的 status 逃生通道。
     */
    public synchronized Task complete(String id) {
        Task t = tasks.get(id);
        if (t == null || !"in_progress".equals(t.getStatus())) {
            return null;
        }
        t.setStatus("completed");
        persist(t);
        return t;
    }

    /**
     * 依赖检查（s12 canStart）：blockedBy 中任一 id 不存在（缺失依赖）或状态≠completed → false；
     * 空 blockedBy → true。
     */
    public synchronized boolean canStart(String id) {
        Task t = tasks.get(id);
        if (t == null) {
            return false;
        }
        for (String depId : t.getBlockedBy()) {
            Task dep = tasks.get(depId);
            if (dep == null || !"completed".equals(dep.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /** 缺失/未完成的依赖 id 列表（供错误提示）。 */
    public synchronized List<String> missingDependencies(String id) {
        Task t = tasks.get(id);
        List<String> missing = new ArrayList<>();
        if (t == null) {
            return missing;
        }
        for (String depId : t.getBlockedBy()) {
            Task dep = tasks.get(depId);
            if (dep == null) {
                missing.add(depId + "(不存在)");
            } else if (!"completed".equals(dep.getStatus())) {
                missing.add(depId + "(" + dep.getStatus() + ")");
            }
        }
        return missing;
    }

    /** 完成任务后变为可开始的下游任务（供 complete 工具报告"解锁了 X, Y"）。 */
    public synchronized List<Task> unlockedBy(String completedId) {
        List<Task> unlocked = new ArrayList<>();
        for (Task t : tasks.values()) {
            if ("pending".equals(t.getStatus())
                    && t.getBlockedBy().contains(completedId)
                    && canStart(t.getId())) {
                unlocked.add(t);
            }
        }
        return unlocked;
    }

    /**
     * 可认领任务扫描（s17 {@code scan_unclaimed_tasks}）：pending 且 owner==null 且依赖全完成。
     * 返回展示序快照副本，空则空列表。供自主队友 idle 期间"找活"。
     */
    public synchronized List<Task> scanUnclaimed() {
        List<Task> ready = new ArrayList<>();
        for (Task t : tasks.values()) {
            if ("pending".equals(t.getStatus()) && t.getOwner() == null && canStart(t.getId())) {
                ready.add(t);
            }
        }
        return ready;
    }

    /**
     * 原子"找一个可认领任务并认领它"（s17 auto-claim 核心原语）：单临界区内取
     * first(pending && owner==null && canStart) 并置 owner + in_progress，防止两个队友并发
     * 认领到同一任务。成功返回该任务；无则 null。插入序遍历 ⇒ 先创建的可认领任务先被领走（FIFO 公平）。
     */
    public synchronized Task scanAndClaimOne(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        for (Task t : tasks.values()) {
            if ("pending".equals(t.getStatus()) && t.getOwner() == null && canStart(t.getId())) {
                t.setOwner(owner);
                t.setStatus("in_progress");
                persist(t);        // 落盘失败降级不抛（与既有 claim 一致）
                return t;
            }
        }
        return null;
    }

    // ── 渲染 ──

    /**
     * 完整清单渲染（终端显示 + 回喂模型确认状态）。仿 TodoWrite 的图标进度视图，
     * 新增：被阻塞的 pending 任务前加 ⛔，附 #id、@owner、被阻塞原因。
     * 空库返回"暂无任务"而非空串（避免 displayNativeToolResult 判空吞掉反馈）。
     */
    public synchronized String render() {
        long done = tasks.values().stream()
                .filter(t -> "completed".equals(t.getStatus()))
                .count();
        StringBuilder sb = new StringBuilder();
        sb.append("Tasks (").append(done).append('/').append(tasks.size()).append(")\n");
        for (Task t : tasks.values()) {
            sb.append("  ");
            boolean blocked = "pending".equals(t.getStatus()) && !canStart(t.getId());
            if (blocked) {
                sb.append("⛔ ");
            }
            sb.append(icon(t.getStatus())).append(" #").append(t.getId()).append(' ').append(t.getSubject());
            if (t.getOwner() != null) {
                sb.append("  @").append(t.getOwner());
            }
            if (blocked) {
                sb.append("  (blocked by ").append(String.join(", ", missingDependencies(t.getId()))).append(')');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 紧凑摘要（供注入 system prompt）：仅未完成任务（pending/in_progress），无 description，
     * 截断到 max 条并附"还有 N 个"。无未完成任务返回 null。
     */
    public synchronized String summarizeOpen(int max) {
        List<Task> open = new ArrayList<>();
        for (Task t : tasks.values()) {
            if ("completed".equals(t.getStatus())) {
                continue;
            }
            open.add(t);
        }
        if (open.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("待办任务（共 ").append(open.size()).append(" 个未完成）：\n");
        int shown = Math.min(max, open.size());
        for (int i = 0; i < shown; i++) {
            Task t = open.get(i);
            sb.append("- [").append(t.getStatus()).append("] #").append(t.getId()).append(' ')
                    .append(t.getSubject());
            if (t.getOwner() != null) {
                sb.append(" @").append(t.getOwner());
            }
            sb.append('\n');
        }
        if (open.size() > shown) {
            sb.append("- …还有 ").append(open.size() - shown).append(" 个\n");
        }
        return sb.toString().stripTrailing();
    }

    public synchronized boolean isEmpty() {
        return tasks.isEmpty();
    }

    public synchronized int size() {
        return tasks.size();
    }

    // ── 私有 ──

    /** 把任务序列化为 JSON 落盘（复用 FileUtils 原子写，自动建父目录）。 */
    private void persist(Task t) {
        try {
            FileUtils.writeFile(dir.resolve(t.getId() + ".json"), MAPPER.writeValueAsString(t));
        } catch (Exception ignored) {
            // 落盘失败不抛：内存态仍可用，下次 load 时会回到磁盘态
        }
    }

    /**
     * 分配下一个 id：读 .seq +1 写回。.seq 缺失则回退为"现存数字 id 最大值+1"（首次启动/手动清理）。
     * 单调递增保证删除任务后不复用旧 id（避免历史会话引用串味）。
     */
    private String nextId() {
        int next;
        Path seq = dir.resolve(SEQ_FILE);
        try {
            if (Files.exists(seq)) {
                next = Integer.parseInt(Files.readString(seq).strip()) + 1;
            } else {
                next = maxNumericId() + 1;
            }
            FileUtils.writeFile(seq, String.valueOf(next));
        } catch (Exception e) {
            next = maxNumericId() + 1;
            try {
                FileUtils.writeFile(seq, String.valueOf(next));
            } catch (Exception ignored) {
                // 计数落盘失败 → 用内存 max+1（可能复用已删 id，可接受降级）
            }
        }
        return String.valueOf(next);
    }

    private int maxNumericId() {
        int max = 0;
        for (String id : tasks.keySet()) {
            Matcher m = NUMERIC_ID.matcher(id);
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(id));
            }
        }
        return max;
    }

    private static String icon(String status) {
        return switch (status) {
            case "completed" -> "✓";
            case "in_progress" -> "▸";
            default -> "○";
        };
    }
}
