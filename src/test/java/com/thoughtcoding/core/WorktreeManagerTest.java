package com.thoughtcoding.core;

import com.thoughtcoding.security.Sandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeManagerTest {

    @TempDir
    Path tempDir;

    private Path repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = tempDir.resolve("repo");
        Files.createDirectories(repository);
        git(repository, "init", "--quiet");
        Files.writeString(repository.resolve("sample.txt"), "main\n", StandardCharsets.UTF_8);
        git(repository, "add", "sample.txt");
        git(repository,
                "-c", "user.name=Test",
                "-c", "user.email=test@example.com",
                "commit", "--quiet", "--no-gpg-sign", "-m", "initial");
        Sandbox.init(repository.toString());
    }

    @Test
    void 有改动时保存独立分支且不修改主工作区() {
        WorktreeManager manager = new WorktreeManager(repository);

        WorktreeManager.RunResult result = manager.run("修改示例", () -> {
            assertNotEquals(repository.toRealPath(), Sandbox.workspaceRoot().toRealPath());
            Files.writeString(Sandbox.resolve("sample.txt"), "subagent\n", StandardCharsets.UTF_8);
            Files.writeString(Sandbox.resolve("new-file.txt"), "new\n", StandardCharsets.UTF_8);
            return "任务完成";
        });

        assertEquals(WorktreeManager.Outcome.Status.SAVED, result.outcome().status());
        assertEquals("main\n", read(repository.resolve("sample.txt")), "主工作区不得被子代理直接修改");
        assertEquals("subagent\n", git(repository, "show",
                result.outcome().branch() + ":sample.txt").replace("\r\n", "\n"));
        assertEquals("new\n", git(repository, "show",
                result.outcome().branch() + ":new-file.txt").replace("\r\n", "\n"));
        assertTrue(result.combinedOutput().contains("git merge --no-ff"));
        assertTrue(git(repository, "status", "--porcelain").isBlank());
    }

    @Test
    void 无改动时自动清理临时分支() {
        WorktreeManager manager = new WorktreeManager(repository);

        WorktreeManager.RunResult result = manager.run("只读检查", () -> {
            assertEquals("main\n", read(Sandbox.resolve("sample.txt")).replace("\r\n", "\n"));
            return "没有改动";
        });

        assertEquals(WorktreeManager.Outcome.Status.NO_CHANGES, result.outcome().status());
        assertFalse(git(repository, "branch", "--list", "thoughtcoding/subagent/*")
                .contains("thoughtcoding/subagent/"));
        assertEquals(1, git(repository, "worktree", "list", "--porcelain")
                .lines().filter(line -> line.startsWith("worktree ")).count());
    }

    @Test
    void 主工作区不干净时拒绝基于过期HEAD执行() throws Exception {
        Files.writeString(repository.resolve("sample.txt"), "uncommitted\n", StandardCharsets.UTF_8);
        WorktreeManager manager = new WorktreeManager(repository);

        WorktreeManager.WorktreeException error = assertThrows(
                WorktreeManager.WorktreeException.class,
                () -> manager.run("危险任务", () -> "不应执行"));

        assertTrue(error.getMessage().contains("主工作区存在未提交文件"));
        assertFalse(git(repository, "branch", "--list", "thoughtcoding/subagent/*")
                .contains("thoughtcoding/subagent/"));
    }

    @Test
    void 子代理自行提交后仍会保留分支而非误判无改动() throws Exception {
        WorktreeManager manager = new WorktreeManager(repository);

        WorktreeManager.RunResult result = manager.run("自行提交", () -> {
            Files.writeString(Sandbox.resolve("sample.txt"), "committed-by-agent\n", StandardCharsets.UTF_8);
            git(Sandbox.workspaceRoot(), "add", "sample.txt");
            git(Sandbox.workspaceRoot(),
                    "-c", "user.name=Agent",
                    "-c", "user.email=agent@example.com",
                    "commit", "--quiet", "--no-gpg-sign", "-m", "agent commit");
            return "已提交";
        });

        assertEquals(WorktreeManager.Outcome.Status.SAVED, result.outcome().status());
        assertEquals("committed-by-agent\n", git(repository, "show",
                result.outcome().branch() + ":sample.txt").replace("\r\n", "\n"));
    }

    @Test
    void 并行子代理拥有互不干扰的线程级workspace和分支() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<WorktreeManager.RunResult>> tasks = List.of(
                    () -> runParallelChange("agent-a.txt", "A\n"),
                    () -> runParallelChange("agent-b.txt", "B\n"));
            List<Future<WorktreeManager.RunResult>> futures = executor.invokeAll(tasks);

            WorktreeManager.RunResult first = futures.get(0).get(30, TimeUnit.SECONDS);
            WorktreeManager.RunResult second = futures.get(1).get(30, TimeUnit.SECONDS);

            assertEquals(WorktreeManager.Outcome.Status.SAVED, first.outcome().status());
            assertEquals(WorktreeManager.Outcome.Status.SAVED, second.outcome().status());
            assertNotEquals(first.outcome().branch(), second.outcome().branch());
            assertEquals("A\n", git(repository, "show",
                    first.outcome().branch() + ":agent-a.txt").replace("\r\n", "\n"));
            assertEquals("B\n", git(repository, "show",
                    second.outcome().branch() + ":agent-b.txt").replace("\r\n", "\n"));
            assertFalse(Files.exists(repository.resolve("agent-a.txt")));
            assertFalse(Files.exists(repository.resolve("agent-b.txt")));
        }
    }

    private WorktreeManager.RunResult runParallelChange(String name, String content) {
        return new WorktreeManager(repository).run(name, () -> {
            Path activeWorkspace = Sandbox.workspaceRoot();
            assertNotEquals(repository.toRealPath(), activeWorkspace.toRealPath());
            Files.writeString(activeWorkspace.resolve(name), content, StandardCharsets.UTF_8);
            return name;
        });
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String git(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Git 命令超时: " + command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "Git 命令失败: " + command + "\n" + output);
            return output;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
