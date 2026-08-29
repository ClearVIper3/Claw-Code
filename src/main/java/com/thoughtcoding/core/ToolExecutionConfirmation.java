package com.thoughtcoding.core;

import com.thoughtcoding.model.ToolExecution;
import com.thoughtcoding.ui.ThoughtCodingUI;
import org.jline.reader.LineReader;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 工具执行确认组件
 * 实现类似 Claude Code 的交互式确认功能
 *
 * <p><b>并发语义</b>：并行子代理可能同时触发确认，终端却只有一个——
 * {@link #CONFIRM_LOCK} 把所有确认框串行化（排队逐个显示），ownerLabel
 * 区分来源（主循环 / 哪个子代理）。
 *
 * <p><b>输入来源</b>：REPL 主线程直接 readLine；agent 回合线程不直接读
 * （LineReader 非线程安全），改经 {@link ConsoleInputRouter} 由主线程投递。
 */
public class ToolExecutionConfirmation {

    /** 全局确认锁：同一时刻终端上只有一个确认框。 */
    private static final ReentrantLock CONFIRM_LOCK = new ReentrantLock();

    private final ThoughtCodingUI ui;
    private final LineReader lineReader;
    private final String ownerLabel;          // null=主循环；否则如 "[SubAgent 调研]"
    private final ConsoleInputRouter router;  // null=无路由（单次提问模式，全程主线程）
    private boolean autoApproveMode = false;

    public ToolExecutionConfirmation(ThoughtCodingUI ui, LineReader lineReader) {
        this(ui, lineReader, null, null);
    }

    public ToolExecutionConfirmation(ThoughtCodingUI ui, LineReader lineReader, String ownerLabel) {
        this(ui, lineReader, ownerLabel, null);
    }

    public ToolExecutionConfirmation(ThoughtCodingUI ui, LineReader lineReader,
                                     String ownerLabel, ConsoleInputRouter router) {
        this.ui = ui;
        this.lineReader = lineReader;
        this.ownerLabel = ownerLabel;
        this.router = router;
    }

    /**
     * 读一行用户输入：主线程直接读；agent 线程经路由器等待主线程投递。
     */
    private String readLine(String prompt) {
        if (router != null && !router.isOnOwnerThread()) {
            return router.awaitLine();
        }
        return lineReader.readLine(prompt);
    }

    /**
     * 智能选项类型
     */
    public enum ActionType {
        YES,           // 同意
        NO                // 拒绝
    }

    /**
     * 询问用户是否执行工具调用（智能 2 选项版本）。
     * 全局锁串行化：并行子代理的确认框排队逐个出现，不互相覆盖。
     */
    public ActionType askConfirmationWithOptions(ToolExecution execution) {
        if (autoApproveMode) {
            ui.displayInfo((ownerLabel == null ? "" : ownerLabel + " ")
                    + "[自动批准模式] 执行: " + execution.toolName());
            return ActionType.YES;
        }

        CONFIRM_LOCK.lock();
        try {
            return askLocked(execution);
        } finally {
            CONFIRM_LOCK.unlock();
        }
    }

    private ActionType askLocked(ToolExecution execution) {
        // 显示智能选项
        displaySmartOptions(execution);

        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                String prompt = "\n" + (ownerLabel == null ? "" : ownerLabel + " ") + "请选择 [1/2]: ";
                String response = readLine(prompt);

                retryCount++;

                if (response == null) {
                    if (retryCount < maxRetries) {
                        ui.displayWarning("⚠️  输入读取失败，正在重试... (" + retryCount + "/" + maxRetries + ")");
                        Thread.sleep(100);
                        continue;
                    } else {
                        ui.displayError("❌ 输入读取失败次数过多，操作已取消");
                        return ActionType.NO;
                    }
                }

                String trimmed = response.trim();

                // 处理用户选择
                ActionType result = switch (trimmed) {
                    case "1" -> {
                        ui.displayInfo("✅ 你选择了：" + getOption1Description(execution.toolName()));
                        yield ActionType.YES;
                    }
                    case "2" -> {
                        ui.displayWarning("⏭️  你选择了：取消操作");
                        yield ActionType.NO;
                    }
                    default -> {
                        ui.displayError("❌ 无效输入，请输入 1 或 2");
                        yield null; // 继续循环
                    }
                };

                // 如果得到了有效结果，返回；否则继续循环
                if (result != null) {
                    return result;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ui.displayError("❌ 操作被中断");
                return ActionType.NO;
            } catch (Exception e) {
                retryCount++;
                if (retryCount < maxRetries) {
                    ui.displayWarning("⚠️  读取输入异常，正在重试... (" + retryCount + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return ActionType.NO;
                    }
                } else {
                    ui.displayError("❌ 读取输入失败: " + e.getMessage());
                    return ActionType.NO;
                }
            }
        }

        return ActionType.NO;
    }

    /**
     * 显示智能选项
     */
    private void displaySmartOptions(ToolExecution execution) {
        ui.getTerminal().writer().println();

        // 展示工具名和参数，避免"无头"确认框
        ui.getTerminal().writer().println("调用: " + execution.toolName() + " " + execution.arguments());
        ui.getTerminal().writer().println();

        ui.getTerminal().writer().println("你想要继续吗？");
        ui.getTerminal().writer().println();

        String toolName = execution.toolName();

        // 🔥 根据工具类型生成不同的选项
        if (toolName.equals("write")) {
            displayCreateFileOptions(execution);
        } else if (toolName.equals("edit")) {
            displayEditFileOptions(execution);
        } else if (toolName.equals("read")) {
            displayReadFileOptions(execution);
        } else if (toolName.equals("bash")) {
            displayExecuteCommandOptions(execution);
        } else if (toolName.equals("glob")) {
            displayGlobOptions(execution);
        } else {
            displayDefaultOptions(execution);
        }

        ui.getTerminal().writer().println();
        ui.getTerminal().writer().flush();
    }

    /**
     * 显示创建文件的选项
     */
    private void displayCreateFileOptions(ToolExecution execution) {
        String fileName = extractFileName(execution);

        ui.getTerminal().writer().println("❯ 1. 是的，创建文件" + fileName);
        ui.getTerminal().writer().println("  2. 丢弃，不创建");
    }

    /**
     * 显示执行命令的选项
     */
    private void displayExecuteCommandOptions(ToolExecution execution) {
        String command = extractCommand(execution);
        boolean isDangerousCommand = command != null && (
            command.contains("rm -rf") ||
            command.contains("git push --force") ||
            command.contains("docker rm") ||
            command.contains("kill -9") ||
            command.contains("sudo")
        );

        if (isDangerousCommand) {
            ui.getTerminal().writer().println("⚠️  这是一个危险命令！");
            ui.getTerminal().writer().println("❯ 1. 是的，我确认要执行");
            ui.getTerminal().writer().println("  2. 取消，不执行");
        } else {
            ui.getTerminal().writer().println("❯ 1. 是的，执行命令");
            ui.getTerminal().writer().println("  2. 取消，不执行");
        }
    }

    /**
     * 显示编辑文件的选项
     */
    private void displayEditFileOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，应用修改");
        ui.getTerminal().writer().println("  2. 取消，不修改");
    }

    /**
     * 显示读取文件的选项
     */
    private void displayReadFileOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，读取文件");
        ui.getTerminal().writer().println("  2. 取消，不读取");
    }

    /**
     * 显示 glob 查找文件的选项
     */
    private void displayGlobOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，执行查找");
        ui.getTerminal().writer().println("  2. 取消，不查找");
    }

    /**
     * 显示默认选项
     */
    private void displayDefaultOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，执行");
        ui.getTerminal().writer().println("  2. 取消");
    }

    /**
     * 从执行参数中提取命令
     */
    private String extractCommand(ToolExecution execution) {
        if (execution.parameters() == null) {
            return null;
        }

        Object command = execution.parameters().get("command");
        if (command != null) {
            return command.toString();
        }

        Object input = execution.parameters().get("input");
        if (input != null) {
            return input.toString();
        }

        return null;
    }

    /**
     * 从执行参数中提取文件名
     */
    private String extractFileName(ToolExecution execution) {
        if (execution.parameters() == null) {
            return null;
        }

        Object path = execution.parameters().get("path");
        if (path != null) {
            String pathStr = path.toString();
            // 提取文件名（去掉路径）
            int lastSlash = pathStr.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < pathStr.length() - 1) {
                return pathStr.substring(lastSlash + 1);
            }
            return pathStr;
        }

        return null;
    }

    /**
     * 获取选项 1 的描述
     */
    private String getOption1Description(String toolName) {
        return switch (toolName) {
            case "write", "write_file" -> "创建文件";
            case "edit", "edit_file" -> "应用修改";
            case "read", "read_file" -> "读取文件";
            case "bash" -> "执行命令";
            default -> "执行操作";
        };
    }

    public void setAutoApproveMode(boolean enabled) {
        this.autoApproveMode = enabled;
        if (enabled) {
            ui.displayInfo("自动批准模式已启用");
        } else {
            ui.displayInfo("交互式确认模式已启用");
        }
    }

    public boolean isAutoApproveMode() {
        return autoApproveMode;
    }
}

