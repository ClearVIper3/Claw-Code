package com.thoughtcoding.core;

import com.thoughtcoding.security.Sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 为一次 SubAgent 执行创建独立 Git worktree，并把结果保存到独立本地分支。
 *
 * <p>隔离边界是“默认工作目录和内置文件工具”，不是操作系统安全沙箱。主工作区必须干净，
 * 这样 worktree 从当前 HEAD 派生时不会悄悄漏掉主工作区尚未提交的代码。完成后：
 * <ul>
 *   <li>无改动：删除 worktree 和临时分支；</li>
 *   <li>有改动：创建本地快照提交，删除 worktree，保留分支供主 Agent 审查/合并；</li>
 *   <li>快照或清理失败：保留 worktree，绝不丢弃 SubAgent 已写入的内容。</li>
 * </ul>
 */
public final class WorktreeManager {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long GIT_TIMEOUT_SECONDS = 60;
    private static final int OUTPUT_LIMIT = 6000;

    private final Path workspace;

    public WorktreeManager(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    /** 在隔离 worktree 中执行任务，并返回任务结论与可供合并的 Git 产物信息。 */
    public RunResult run(String label, Callable<String> action) {
        Repository repository = inspectRepository();
        ensureClean(repository.root());

        String id = ID_TIME.format(LocalDateTime.now()) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String branch = "thoughtcoding/subagent/" + id;
        Path storageRoot = storageRoot(repository.root());
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new WorktreeException("无法创建 SubAgent worktree 临时目录: " + storageRoot, e);
        }
        Path worktreeRoot = storageRoot.resolve(id).toAbsolutePath().normalize();
        if (!worktreeRoot.startsWith(storageRoot.toAbsolutePath().normalize())) {
            throw new WorktreeException("临时 worktree 路径越界: " + worktreeRoot);
        }

        try {
            git(repository.root(), "worktree", "add", "--quiet", "-b", branch,
                    worktreeRoot.toString(), repository.baseCommit());
        } catch (Exception e) {
            cleanupFailedCreation(repository.root(), worktreeRoot, branch);
            throw new WorktreeException("创建 SubAgent worktree 失败: " + e.getMessage(), e);
        }

        Path scopedWorkspace = worktreeRoot.resolve(repository.workspaceRelativePath()).normalize();
        if (!scopedWorkspace.startsWith(worktreeRoot) || !Files.isDirectory(scopedWorkspace)) {
            cleanupFailedCreation(repository.root(), worktreeRoot, branch);
            throw new WorktreeException("worktree 中找不到原工作目录对应位置: " + scopedWorkspace);
        }

        String conclusion = null;
        Throwable failure = null;
        try (Sandbox.WorkspaceScope ignored = Sandbox.enterWorkspace(scopedWorkspace)) {
            conclusion = action.call();
        } catch (Throwable t) {
            failure = t;
        }

        Outcome outcome = finish(repository, worktreeRoot, branch, label);

        if (failure != null) {
            if (failure instanceof Error error) {
                throw error;
            }
            String message = "SubAgent 在隔离 worktree 中执行失败: " + safeMessage(failure)
                    + "\n\n" + outcome.summary();
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WorktreeException(message, failure);
        }
        return new RunResult(conclusion, outcome);
    }

    private Repository inspectRepository() {
        String rootText = git(workspace, "rev-parse", "--show-toplevel").trim();
        String baseCommit = git(workspace, "rev-parse", "HEAD").trim();
        if (rootText.isBlank() || baseCommit.isBlank()) {
            throw new WorktreeException("当前目录不是包含有效 HEAD 的 Git 仓库: " + workspace);
        }
        Path root = Path.of(rootText).toAbsolutePath().normalize();
        if (!workspace.startsWith(root)) {
            throw new WorktreeException("当前 workspace 不在 Git 仓库根目录内: " + workspace);
        }
        return new Repository(root, root.relativize(workspace), baseCommit);
    }

    private void ensureClean(Path repositoryRoot) {
        String status = git(repositoryRoot, "status", "--porcelain", "--untracked-files=all").trim();
        if (!status.isBlank()) {
            throw new WorktreeException(
                    "无法安全创建 SubAgent worktree：主工作区存在未提交文件。"
                    + "请先提交、暂存到其他分支或清理这些改动；否则新 worktree 不会包含它们。\n"
                    + truncate(status));
        }
    }

    private Outcome finish(Repository repository, Path worktreeRoot, String branch, String label) {
        try {
            String status = git(worktreeRoot, "status", "--porcelain", "--untracked-files=all").trim();
            if (!status.isBlank()) {
                git(worktreeRoot, "add", "-A");
                String message = "SubAgent: " + sanitizeLabel(label);
                git(worktreeRoot,
                        "-c", "user.name=ThoughtCoding SubAgent",
                        "-c", "user.email=subagent@thoughtcoding.local",
                        "commit", "--quiet", "--no-verify", "--no-gpg-sign", "-m", message);
            }

            String head = git(worktreeRoot, "rev-parse", "HEAD").trim();
            if (head.equals(repository.baseCommit())) {
                removeWorktree(repository.root(), worktreeRoot);
                git(repository.root(), "branch", "-D", branch);
                return Outcome.noChanges();
            }

            String files = git(worktreeRoot, "diff", "--name-status",
                    repository.baseCommit() + ".." + head).trim();
            String stat = git(worktreeRoot, "diff", "--stat",
                    repository.baseCommit() + ".." + head).trim();
            try {
                removeWorktree(repository.root(), worktreeRoot);
                return Outcome.saved(branch, head, files, stat);
            } catch (Exception cleanupError) {
                return Outcome.savedButPreserved(branch, head, worktreeRoot, files, stat,
                        "快照已保存，但临时目录清理失败: " + cleanupError.getMessage());
            }
        } catch (Exception snapshotError) {
            return Outcome.preserved(branch, worktreeRoot,
                    "无法自动保存/清理，请在该目录手动检查: " + snapshotError.getMessage());
        }
    }

    private void removeWorktree(Path repositoryRoot, Path worktreeRoot) {
        Path storage = storageRoot(repositoryRoot).toAbsolutePath().normalize();
        Path target = worktreeRoot.toAbsolutePath().normalize();
        if (!target.startsWith(storage) || target.equals(storage)) {
            throw new WorktreeException("拒绝清理不安全的 worktree 路径: " + target);
        }
        git(repositoryRoot, "worktree", "remove", "--force", target.toString());
        git(repositoryRoot, "worktree", "prune");
    }

    private void cleanupFailedCreation(Path repositoryRoot, Path worktreeRoot, String branch) {
        try {
            if (Files.exists(worktreeRoot)) {
                removeWorktree(repositoryRoot, worktreeRoot);
            }
        } catch (Exception ignored) {
        }
        try {
            git(repositoryRoot, "branch", "-D", branch);
        } catch (Exception ignored) {
        }
    }

    private Path storageRoot(Path repositoryRoot) {
        String repoKey = Integer.toUnsignedString(
                repositoryRoot.toAbsolutePath().normalize().toString().toLowerCase().hashCode(), 36);
        return Path.of(System.getProperty("java.io.tmpdir"), "thoughtcoding-worktrees", repoKey)
                .toAbsolutePath().normalize();
    }

    private String git(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(Arrays.asList(args));

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            // 与进程并发消费输出，避免大量 diff/status 输出塞满管道后双方互等。
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    throw new WorktreeException("读取 Git 输出失败: " + e.getMessage(), e);
                }
            });
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new WorktreeException("Git 命令超时: " + String.join(" ", command));
            }
            String output = outputFuture.join();
            if (process.exitValue() != 0) {
                throw new WorktreeException("Git 命令失败(" + process.exitValue() + "): "
                        + String.join(" ", command) + (output.isBlank() ? "" : "\n" + truncate(output)));
            }
            return output;
        } catch (IOException e) {
            throw new WorktreeException("无法启动 Git，请确认 git 已安装并在 PATH 中: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorktreeException("Git 命令被中断", e);
        }
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) return "task";
        String oneLine = label.replaceAll("[\\r\\n]+", " ").trim();
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100);
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() == null || t.getMessage().isBlank()
                ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= OUTPUT_LIMIT) return text;
        return text.substring(0, OUTPUT_LIMIT) + "\n...(输出已截断)";
    }

    private record Repository(Path root, Path workspaceRelativePath, String baseCommit) {}

    public record RunResult(String conclusion, Outcome outcome) {
        public String combinedOutput() {
            String text = conclusion == null || conclusion.isBlank()
                    ? "子Agent已结束，但未产出文本结论。" : conclusion;
            return text + "\n\n" + outcome.summary();
        }
    }

    public record Outcome(Status status, String branch, String commit, Path worktree,
                          String files, String stat, String note) {
        public enum Status { NO_CHANGES, SAVED, PRESERVED }

        static Outcome noChanges() {
            return new Outcome(Status.NO_CHANGES, null, null, null, null, null, null);
        }

        static Outcome saved(String branch, String commit, String files, String stat) {
            return new Outcome(Status.SAVED, branch, commit, null, files, stat, null);
        }

        static Outcome savedButPreserved(String branch, String commit, Path worktree,
                                         String files, String stat, String note) {
            return new Outcome(Status.PRESERVED, branch, commit, worktree, files, stat, note);
        }

        static Outcome preserved(String branch, Path worktree, String note) {
            return new Outcome(Status.PRESERVED, branch, null, worktree, null, null, note);
        }

        public String summary() {
            return switch (status) {
                case NO_CHANGES -> "[Git Worktree 隔离]\n未产生文件改动；临时 worktree 和分支已清理。";
                case SAVED -> "[Git Worktree 隔离]\n"
                        + "改动已保存到独立本地分支，主工作区未修改。\n"
                        + "分支: " + branch + "\n"
                        + "提交: " + commit + "\n"
                        + (files == null || files.isBlank() ? "" : "文件:\n" + truncate(files) + "\n")
                        + (stat == null || stat.isBlank() ? "" : "统计:\n" + truncate(stat) + "\n")
                        + "确认后可在主工作区执行: git merge --no-ff " + branch;
                case PRESERVED -> "[Git Worktree 隔离]\n"
                        + "为避免丢失内容，临时 worktree 已保留，请手动检查。\n"
                        + (branch == null ? "" : "分支: " + branch + "\n")
                        + (commit == null ? "" : "提交: " + commit + "\n")
                        + (worktree == null ? "" : "目录: " + worktree + "\n")
                        + (files == null || files.isBlank() ? "" : "文件:\n" + truncate(files) + "\n")
                        + (note == null ? "" : "说明: " + note);
            };
        }
    }

    public static class WorktreeException extends RuntimeException {
        public WorktreeException(String message) {
            super(message);
        }

        public WorktreeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
