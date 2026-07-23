package com.thoughtcoding.core;

import com.thoughtcoding.model.ToolExecution;
import com.thoughtcoding.ui.ThoughtCodingUI;
import org.jline.reader.LineReader;

/**
 * 工具执行确认组件
 * 实现类似 Claude Code 的交互式确认功能
 */
public class ToolExecutionConfirmation {
    private final ThoughtCodingUI ui;
    private final LineReader lineReader;
    private boolean autoApproveMode = false;

    public ToolExecutionConfirmation(ThoughtCodingUI ui, LineReader lineReader) {
        this.ui = ui;
        this.lineReader = lineReader;
    }

    /**
     * 智能选项类型
     */
    public enum ActionType {
        CREATE_ONLY,           // 仅创建
        CREATE_AND_RUN,        // 创建并运行
        DISCARD                // 丢弃
    }

    /**
     * 询问用户是否执行工具调用（智能 3 选项版本）
     */
    public ActionType askConfirmationWithOptions(ToolExecution execution) {
        if (autoApproveMode) {
            ui.displayInfo("🤖 [自动批准模式] 执行: " + execution.toolName());
            return ActionType.CREATE_ONLY;
        }

        // 显示智能选项
        displaySmartOptions(execution);

        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                String prompt = "\n请选择 [1/2/3]: ";
                String response = lineReader.readLine(prompt);

                retryCount++;

                if (response == null) {
                    if (retryCount < maxRetries) {
                        ui.displayWarning("⚠️  输入读取失败，正在重试... (" + retryCount + "/" + maxRetries + ")");
                        Thread.sleep(100);
                        continue;
                    } else {
                        ui.displayError("❌ 输入读取失败次数过多，操作已取消");
                        return ActionType.DISCARD;
                    }
                }

                String trimmed = response.trim();

                // 处理用户选择
                ActionType result = switch (trimmed) {
                    case "1" -> {
                        ui.displayInfo("✅ 你选择了：" + getOption1Description(execution.toolName()));
                        yield ActionType.CREATE_ONLY;
                    }
                    case "2" -> {
                        ui.displayInfo("✅ 你选择了：" + getOption2Description(execution.toolName()));
                        yield ActionType.CREATE_AND_RUN;
                    }
                    case "3" -> {
                        ui.displayWarning("⏭️  你选择了：取消操作");
                        yield ActionType.DISCARD;
                    }
                    default -> {
                        ui.displayError("❌ 无效输入，请输入 1、2 或 3");
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
                return ActionType.DISCARD;
            } catch (Exception e) {
                retryCount++;
                if (retryCount < maxRetries) {
                    ui.displayWarning("⚠️  读取输入异常，正在重试... (" + retryCount + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return ActionType.DISCARD;
                    }
                } else {
                    ui.displayError("❌ 读取输入失败: " + e.getMessage());
                    return ActionType.DISCARD;
                }
            }
        }

        return ActionType.DISCARD;
    }

    /**
     * 显示智能选项
     */
    private void displaySmartOptions(ToolExecution execution) {
        ui.getTerminal().writer().println();
        ui.getTerminal().writer().println("你想要继续吗？");
        ui.getTerminal().writer().println();

        String toolName = execution.toolName();

        // 🔥 根据工具类型生成不同的选项
        if (toolName.equals("write_file")) {
            // 创建文件的选项
            displayCreateFileOptions(execution);
        } else if (toolName.equals("file_manager")) {
            // 原生 file_manager：按 command 细分
            String cmd = extractFileManagerCommand(execution);
            if ("write".equals(cmd) || "create".equals(cmd)) {
                displayCreateFileOptions(execution);
            } else if ("delete".equals(cmd)) {
                ui.getTerminal().writer().println("⚠️  这将删除文件/目录！");
                ui.getTerminal().writer().println("❯ 1. 是的，确认删除");
                ui.getTerminal().writer().println("  2. 我再想想");
                ui.getTerminal().writer().println("  3. 取消");
            } else if ("read".equals(cmd)) {
                displayReadFileOptions(execution);
            } else if ("list".equals(cmd)) {
                displayListDirectoryOptions(execution);
            } else {
                displayDefaultOptions(execution);
            }
        } else if (toolName.equals("bash") || toolName.equals("command_executor") || toolName.equals("code_executor")) {
            // 执行命令/代码的选项
            displayExecuteCommandOptions(execution);
        } else if (toolName.equals("edit_file")) {
            // 编辑文件的选项
            displayEditFileOptions(execution);
        } else if (toolName.equals("read_file")) {
            // 读取文件的选项
            displayReadFileOptions(execution);
        } else if (toolName.equals("list_directory")) {
            // 列出目录的选项
            displayListDirectoryOptions(execution);
        } else {
            // 默认选项
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
        boolean isJavaFile = fileName != null && fileName.endsWith(".java");
        boolean isPythonFile = fileName != null && fileName.endsWith(".py");
        boolean isScriptFile = fileName != null && (fileName.endsWith(".sh") || fileName.endsWith(".bat"));

        ui.getTerminal().writer().println("❯ 1. 是的，创建文件");

        if (isJavaFile) {
            ui.getTerminal().writer().println("  2. 创建并立即编译运行 (javac + java)");
        } else if (isPythonFile) {
            ui.getTerminal().writer().println("  2. 创建并立即运行 (python3)");
        } else if (isScriptFile) {
            ui.getTerminal().writer().println("  2. 创建并立即执行 (chmod +x && run)");
        } else {
            ui.getTerminal().writer().println("  2. 创建并打开编辑器");
        }

        ui.getTerminal().writer().println("  3. 丢弃，不创建");
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
            ui.getTerminal().writer().println("  2. 让我再想想，暂时不执行");
            ui.getTerminal().writer().println("  3. 取消，不执行");
        } else {
            ui.getTerminal().writer().println("❯ 1. 是的，执行命令");
            ui.getTerminal().writer().println("  2. 查看命令详情后再决定");
            ui.getTerminal().writer().println("  3. 取消，不执行");
        }
    }

    /**
     * 显示编辑文件的选项
     */
    private void displayEditFileOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，应用修改");
        ui.getTerminal().writer().println("  2. 应用并打开编辑器查看");
        ui.getTerminal().writer().println("  3. 取消，不修改");
    }

    /**
     * 显示读取文件的选项
     */
    private void displayReadFileOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，读取文件");
        ui.getTerminal().writer().println("  2. 读取并使用分页器查看");
        ui.getTerminal().writer().println("  3. 取消，不读取");
    }

    /**
     * 显示列出目录的选项
     */
    private void displayListDirectoryOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，列出目录内容");
        ui.getTerminal().writer().println("  2. 列出并显示详细信息");
        ui.getTerminal().writer().println("  3. 取消，不列出");
    }

    /**
     * 显示默认选项
     */
    private void displayDefaultOptions(ToolExecution execution) {
        ui.getTerminal().writer().println("❯ 1. 是的，执行");
        ui.getTerminal().writer().println("  2. 执行并查看详情");
        ui.getTerminal().writer().println("  3. 取消");
    }

    /** 提取 file_manager 的 command 参数（read/write/list/create/delete/info） */
    private String extractFileManagerCommand(ToolExecution execution) {
        if (execution.parameters() == null) {
            return null;
        }
        Object c = execution.parameters().get("command");
        return c == null ? null : c.toString().toLowerCase();
    }

    /**
     * 从执行参数中提取命令
     */
    private String extractCommand(ToolExecution execution) {        if (execution.parameters() == null) {
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
     * 兼容旧版本的简单确认（保留以防某些地方还在使用）
     */
    public boolean askConfirmation(ToolExecution execution) {
        ActionType action = askConfirmationWithOptions(execution);
        return action == ActionType.CREATE_ONLY || action == ActionType.CREATE_AND_RUN;
    }

    /**
     * 获取选项 1 的描述
     */
    private String getOption1Description(String toolName) {
        return switch (toolName) {
            case "write_file" -> "创建文件";
            case "command_executor" -> "执行命令";
            case "code_executor" -> "执行代码";
            case "file_manager" -> "执行文件操作";
            case "edit_file" -> "应用修改";
            case "read_file" -> "读取文件";
            case "list_directory" -> "列出目录";
            default -> "执行操作";
        };
    }

    /**
     * 获取选项 2 的描述
     */
    private String getOption2Description(String toolName) {
        return switch (toolName) {
            case "write_file" -> "创建并运行";
            case "command_executor" -> "查看详情";
            case "code_executor" -> "执行代码";
            case "file_manager" -> "查看详情";
            case "edit_file" -> "应用并查看";
            case "read_file" -> "读取并分页查看";
            case "list_directory" -> "列出详细信息";
            default -> "执行并查看详情";
        };
    }

    public void setAutoApproveMode(boolean enabled) {
        this.autoApproveMode = enabled;
        if (enabled) {
            ui.displayInfo("🤖 自动批准模式已启用");
        } else {
            ui.displayInfo("👤 交互式确认模式已启用");
        }
    }

    public boolean isAutoApproveMode() {
        return autoApproveMode;
    }

    /**
     * 🔥 简化的确认方法，用于流式触发的工具调用
     * 这种情况下，确认框已经在流式输出中显示了，只需要询问用户是否执行
     */
    public boolean askSimpleConfirmation() {
        if (autoApproveMode) {
            ui.displayInfo("🤖 [自动批准模式] 执行");
            return true;
        }

        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                String prompt = "\n执行此操作？ [yes/no/auto/skip]: ";
                String response = lineReader.readLine(prompt);

                if (response == null) {
                    retryCount++;
                    if (retryCount < maxRetries) {
                        ui.displayWarning("⚠️  输入读取失败，正在重试... (" + retryCount + "/" + maxRetries + ")");
                        Thread.sleep(100);
                        continue;
                    } else {
                        ui.displayError("❌ 输入读取失败次数过多，操作已取消");
                        return false;
                    }
                }

                String trimmedResponse = response.toLowerCase().trim();

                switch (trimmedResponse) {
                    case "y":
                    case "yes":
                        return true;
                    case "n":
                    case "no":
                        ui.displayWarning("⏭️  用户拒绝执行");
                        return false;
                    case "auto":
                        ui.displayInfo("🤖 已启用自动批准模式");
                        autoApproveMode = true;
                        return true;
                    case "skip":
                    case "s":
                        ui.displayWarning("⏭️  已跳过此操作");
                        return false;
                    default:
                        ui.displayError("❌ 无效输入，请输入: yes/no/auto/skip");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ui.displayError("❌ 操作被中断");
                return false;
            } catch (Exception e) {
                retryCount++;
                if (retryCount < maxRetries) {
                    ui.displayWarning("⚠️  读取输入异常，正在重试... (" + retryCount + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    ui.displayError("❌ 读取输入失败: " + e.getMessage());
                    return false;
                }
            }
        }

        return false;
    }
}

