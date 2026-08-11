package com.thoughtcoding.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thoughtcoding.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 定时任务(cron)存储层 —— 仿 {@code TaskStore} 的容错持久化，但用<b>单文件</b> JSON。
 *
 * <p>文件路径（相对工作目录）：{@code <工作目录>/.scheduled_tasks.json}。
 * 与 task 的每任务一文件不同：cron 任务数量少，单文件即可；落盘时<b>只写 durable=true</b> 的任务
 * （session-only 任务仅存活于内存，重启不恢复——对齐 s14 语义）。
 *
 * <p>容错风格对齐 {@code TaskStore}：load 时逐条校验 cron 表达式、跳过非法条目，任何异常降级为空库、
 * 绝不抛；写盘失败保留内存态。所有 mutator 加 {@code synchronized}——工具线程 / 轮询线程 / 消费者线程都会碰。
 */
public final class CronStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final Path file;
    private final Map<String, CronJob> jobs; // id → CronJob，写入序即展示序

    private CronStore(Path file, Map<String, CronJob> jobs) {
        this.file = file;
        this.jobs = jobs;
    }

    /**
     * 从单文件加载（或创建）cron 存储。文件不存在视为空库；解析/校验失败的条目跳过。
     * 返回的实例可直接用 CRUD 方法修改。
     *
     * <p><b>必须传绝对路径</b>：{@code FileUtils.writeFile} 内部对 {@code path.getParent()} 建目录，
     * 裸文件名会 NPE。调用方用 {@code Paths.get(System.getProperty("user.dir"), ".scheduled_tasks.json")}。
     */
    public static CronStore load(Path file) {
        Map<String, CronJob> map = new LinkedHashMap<>();
        if (file != null && Files.isRegularFile(file)) {
            try {
                List<CronJob> loaded = MAPPER.readValue(file.toFile(), MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, CronJob.class));
                if (loaded != null) {
                    for (CronJob job : loaded) {
                        if (job == null || job.getId() == null) {
                            continue;
                        }
                        // 非法 cron 的任务跳过（不阻塞其余加载）
                        if (CronExpression.validate(job.getCron()) != null) {
                            continue;
                        }
                        map.put(job.getId(), job);
                    }
                }
            } catch (Exception ignored) {
                // 文件损坏/反序列化失败 → 降级为空库
            }
        }
        return new CronStore(file, map);
    }

    /**
     * 注册新任务：先校验 cron 表达式，非法返回错误串；合法则分配 id、按 durable 落盘后返回。
     * id 用 {@code cron_ + 6 位随机}（对齐 s14）。
     */
    public synchronized String schedule(String cron, String prompt, boolean recurring, boolean durable) {
        String err = CronExpression.validate(cron);
        if (err != null) {
            return err;
        }
        String id = "cron_" + ThreadLocalRandom.current().nextInt(1000000);
        CronJob job = new CronJob(id, cron, prompt, recurring, durable);
        jobs.put(id, job);
        if (durable) {
            persistDurable();
        }
        return id;
    }

    /** 取消任务：存在则移除（durable 同步落盘），返回是否取消成功。 */
    public synchronized boolean cancel(String id) {
        CronJob removed = jobs.remove(id);
        if (removed != null && removed.isDurable()) {
            persistDurable();
        }
        return removed != null;
    }

    public synchronized List<CronJob> list() {
        return new ArrayList<>(jobs.values());
    }

    public synchronized boolean isEmpty() {
        return jobs.isEmpty();
    }

    public synchronized int size() {
        return jobs.size();
    }

    /** 完整清单渲染（cron_list 工具回馈模型 + 终端显示）。空库返回"暂无定时任务"而非空串（对齐 TaskStore.render 判空防吞）。 */
    public synchronized String render() {
        if (jobs.isEmpty()) {
            return "暂无定时任务。可用 cron_schedule 登记一个（cron: 5 段表达式, prompt: 到点执行的提示）。";
        }
        StringBuilder sb = new StringBuilder("Scheduled cron jobs (").append(jobs.size()).append("):\n");
        for (CronJob j : jobs.values()) {
            String tag = j.isRecurring() ? "recurring" : "one-shot";
            String dur = j.isDurable() ? "durable" : "session";
            sb.append("  ").append(j.getId()).append("  '").append(j.getCron()).append("'  → ")
                    .append(j.getPrompt()).append("  [").append(tag).append(", ").append(dur).append("]\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 仅把 durable 任务写盘（session-only 不落盘）。写盘失败保留内存态，下次 load 回到磁盘态。 */
    private synchronized void persistDurable() {
        try {
            List<CronJob> durable = jobs.values().stream().filter(CronJob::isDurable).toList();
            FileUtils.writeFile(file, MAPPER.writeValueAsString(durable));
        } catch (Exception ignored) {
            // 落盘失败不抛：内存态仍可用
        }
    }
}
