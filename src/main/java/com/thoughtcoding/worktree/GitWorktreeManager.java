package com.thoughtcoding.worktree;

import com.thoughtcoding.util.FileUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * git worktree 管理器（s18 Worktree Isolation）—— Lead 侧三个 worktree 工具的实现后端。
 *
 * <p>布局（对齐参考设计 s18）：
 * <pre>
 *   &lt;repo&gt;/
 *     .worktrees/&lt;name&gt;/   ← 每个 worktree 一个独立检出（分支 wt/&lt;name&gt;）
 *     .worktrees/events.jsonl  ← 生命周期事件（create/remove/keep），追加写
 * </pre>
 *
 * <p>所有 git 调用经 {@link #runGit(List)}（无 shell，参数列表直传，避免 PowerShell 引号陷阱），
 * 变更多操作（create/remove/keep）加 {@code synchronized} 串行化，避免并发队友与 lead 争抢
 * {@code git worktree} 元数据锁。读操作（status / rev-list）不加锁。任何失败都以
 * {@link GitResult} 返回、不抛出（对齐本仓库"工具永不逃逸异常"的惯例）。
 */
public final class GitWorktreeManager {

    /** 合法 worktree 名：字母/数字/点/下划线/短横线，1–64 字符（对齐 s18 VALID_WT_NAME）。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** 单次 git 命令超时秒数。 */
    private static final int GIT_TIMEOUT_SECONDS = 30;

    /** 事件日志用紧凑 ObjectMapper（单行 JSON，不开 INDENT_OUTPUT——JsonUtils 是美化版会破坏 JSONL）。 */
    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

    private final Path repoRoot;
    private final String baseDir;

    /**
     * @param repoRoot 仓库根（git 命令的 cwd，绝对路径）
     * @param baseDir  worktree 根目录名（相对仓库根，如 ".worktrees"）
     */
    public GitWorktreeManager(Path repoRoot, String baseDir) {
        this.repoRoot = repoRoot;
        this.baseDir = (baseDir == null || baseDir.isBlank()) ? ".worktrees" : baseDir;
    }

    /** git 命令结果：ok=exit 0，output=合并的 stdout/stderr（截断到 5000 字符）。 */
    public record GitResult(boolean ok, String output) {
    }

    // ── 名称校验与路径 ──

    /** 校验 worktree 名：返回 null 表示合法，否则返回错误文案（对齐 s18 validate_worktree_name）。 */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "worktree 名不能为空";
        }
        if (name.equals(".") || name.equals("..")) {
            return "'" + name + "' 不是合法的 worktree 名";
        }
        if (!VALID_NAME.matcher(name).matches()) {
            return "非法 worktree 名 '" + name + "'：仅允许字母/数字/点/下划线/短横线（1-64 字符）";
        }
        return null;
    }

    /** worktree 目录的绝对路径：{@code <repoRoot>/<baseDir>/<name>}。 */
    public Path worktreeDir(String name) {
        return repoRoot.resolve(baseDir).resolve(name);
    }

    /** 探测本机 git 是否可用（启动时用于优雅降级：不可用则不注册 worktree 工具）。 */
    public static boolean gitAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 生命周期：create / remove / keep ──

    /**
     * 创建 worktree：{@code git worktree add <baseDir>/<name> -b wt/<name> HEAD}。
     * 名字非法或目录已存在则失败返回；成功后追加 create 事件。
     */
    /**
     * 创建 worktree：{@code git worktree add <baseDir>/<name> -b wt/<name> HEAD}。
     * 名字非法或目录已存在则失败返回；成功后追加 create 事件。
     */
    public synchronized GitResult createWorktree(String name) {
        String err = validateName(name);
        if (err != null) {
            return new GitResult(false, err);
        }
        Path dir = worktreeDir(name);
        if (Files.exists(dir)) {
            return new GitResult(false, "worktree '" + name + "' 已存在: " + dir);
        }
        GitResult r = runGit(Arrays.asList(
                "worktree", "add", baseDir + "/" + name, "-b", "wt/" + name, "HEAD"));
        if (!r.ok()) {
            return new GitResult(false, "git 创建 worktree 失败: " + r.output());
        }
        logEvent("create", name);
        return new GitResult(true, "worktree '" + name + "' 已创建于 " + dir + "（分支 wt/" + name + "）");
    }

    /**
     * 移除 worktree。除非 {@code discardChanges}，否则先做安全检查：
     * 有未提交改动（{@code git status --porcelain} 非空）或有未推送提交
     * （{@code rev-list --count wt/<name> --not --exclude=refs/heads/wt/<name> --all} &gt; 0）则拒绝。
     * 通过后 {@code git worktree remove --force} + {@code git branch -D wt/<name>}；追加 remove 事件。
     */
    public synchronized GitResult removeWorktree(String name, boolean discardChanges) {
        String err = validateName(name);
        if (err != null) {
            return new GitResult(false, err);
        }
        Path dir = worktreeDir(name);
        if (!Files.exists(dir)) {
            return new GitResult(false, "worktree '" + name + "' 不存在: " + dir);
        }
        if (!discardChanges) {
            int uncommitted = countUncommitted(name);
            int unpushed = countUnpushed(name);
            if (uncommitted < 0 || unpushed < 0) {
                return new GitResult(false, "无法确认 worktree '" + name + "' 的状态（git 检查失败）。"
                        + "如确认要丢弃，请用 discard_changes=true 强制移除。");
            }
            if (uncommitted > 0 || unpushed > 0) {
                return new GitResult(false, "worktree '" + name + "' 有 " + uncommitted
                        + " 个未提交文件、" + unpushed + " 个未推送提交，拒绝移除。"
                        + "用 discard_changes=true 强制移除，或 keep_worktree 保留待人工审查。");
            }
        }
        GitResult r = runGit(Arrays.asList("worktree", "remove", baseDir + "/" + name, "--force"));
        if (!r.ok()) {
            return new GitResult(false, "git 移除 worktree 失败: " + r.output());
        }
        runGit(Arrays.asList("branch", "-D", "wt/" + name)); // 删分支失败不阻塞（worktree 已移除）
        logEvent("remove", name);
        return new GitResult(true, "worktree '" + name + "' 已移除（分支 wt/" + name + " 已删除）");
    }

    /** 保留 worktree 待人工审查（目录与分支均不动）；仅追加 keep 事件。 */
    public synchronized GitResult keepWorktree(String name) {
        String err = validateName(name);
        if (err != null) {
            return new GitResult(false, err);
        }
        logEvent("keep", name);
        return new GitResult(true, "worktree '" + name + "' 已保留待审查（分支 wt/" + name + " 未删除）");
    }

    // ── 安全检查辅助 ──

    /** 未提交改动文件数（git status --porcelain 行数）；失败返回 -1。 */
    public int countUncommitted(String name) {
        GitResult r = runGitIn(worktreeDir(name), Arrays.asList("status", "--porcelain"));
        if (!r.ok()) {
            return -1;
        }
        return countLines(r.output());
    }

    /**
     * 未推送提交数：{@code wt/<name>} 上不存在于任何其他分支/远端的提交数。
     * 用 {@code --not --exclude=... --all} 而非 @{push}/@{upstream}——新建分支没有上游，
     * @{push} 会直接报错（参考实现的 _count_worktree_changes 因此返回 -1,-1）。
     * 失败返回 -1。
     */
    public int countUnpushed(String name) {
        String branch = "wt/" + name;
        GitResult r = runGit(Arrays.asList("rev-list", "--count", branch,
                "--not", "--exclude=refs/heads/" + branch, "--all"));
        if (!r.ok()) {
            return -1;
        }
        try {
            return Integer.parseInt(r.output().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── git 执行 ──

    /** 在仓库根执行 git。返回 (ok, 输出)；超时/异常都收敛为 ok=false，不抛出。 */
    public GitResult runGit(List<String> args) {
        return runGitIn(repoRoot, args);
    }

    /** 在指定目录执行 git（如对某个 worktree 跑 status，让其反映该副本的 index）。 */
    public GitResult runGitIn(Path cwd, List<String> args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception ignored) {
                    // 进程被 destroy 后流关闭，忽略
                }
            }, "git-stdout-reader");
            readerThread.start();

            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                return new GitResult(false, "git 命令超时（>" + GIT_TIMEOUT_SECONDS + "s）");
            }
            readerThread.join(5000);

            String out = output.toString().trim();
            if (out.length() > 5000) {
                out = out.substring(0, 5000);
            }
            return new GitResult(process.exitValue() == 0, out.isEmpty() ? "(无输出)" : out);
        } catch (Exception e) {
            return new GitResult(false, "git 执行失败: " + e.getMessage());
        }
    }

    // ── 事件日志 ──

    /** 追加一条生命周期事件到 {@code <baseDir>/events.jsonl}（失败静默，不阻塞工具）。 */
    private void logEvent(String type, String name) {
        try {
            Path dir = repoRoot.resolve(baseDir);
            Files.createDirectories(dir);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("worktree", name);
            event.put("ts", System.currentTimeMillis());
            FileUtils.appendToFile(dir.resolve("events.jsonl"),
                    COMPACT_MAPPER.writeValueAsString(event));
        } catch (Exception ignored) {
            // 事件落盘失败不影响主流程
        }
    }

    private static int countLines(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String line : s.split("\n")) {
            if (!line.isBlank()) {
                n++;
            }
        }
        return n;
    }
}
