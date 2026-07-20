package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolExecution;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.tools.ToolDispatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 在ThoughtCodingCommand中管理AI交互的核心循环
 *
 * AgentLoop启动和协调 ，AI对话、工具调用、会话管理等
 *
 * 流程协调：管理从用户输入到AI响应的完整流程
 *
 * 工具调度：协调AI模型与工具系统的交互
 *
 * 状态管理：维护对话状态和上下文
 *
 * 错误处理：处理整个流程中的异常情况
 */
public class AgentLoop {
    private final ThoughtCodingContext context;
    private final List<ChatMessage> history;
    private final String sessionId;
    private final String modelName;
    private final ToolExecutionConfirmation confirmation;  // 🔥 新增：交互式确认组件
    private final OptionManager optionManager;  // 🔥 新增：选项管理器
    private final ToolDispatcher toolDispatcher;  // 🔥 工具执行唯一收口（沙箱插桩点）

    public AgentLoop(ThoughtCodingContext context, String sessionId, String modelName) {
        this.context = context;
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = new ArrayList<>();

        // 🔥 创建交互式确认组件
        this.confirmation = new ToolExecutionConfirmation(
            context.getUi(),
            context.getUi().getLineReader()
        );

        // 🔥 创建选项管理器
        this.optionManager = new OptionManager();

        // 🔥 创建工具执行收口
        this.toolDispatcher = new ToolDispatcher(context.getToolRegistry());

        // 设置消息和工具调用处理器
        context.getAiService().setMessageHandler(this::handleMessage);
        context.getAiService().setToolCallHandler(this::handleToolCall);
    }

    public void loadHistory(List<ChatMessage> previousHistory) {
        if (previousHistory != null) {
            history.addAll(previousHistory);
        }
    }

    public void processInput(String input) {
        // 开始性能监控
        PerformanceMonitor monitor = context.getPerformanceMonitor();
        monitor.start();

        try {
            // 🔥 检查是否是选项输入（用户输入 1/2/3/4 选择）
            if (optionManager.isOptionInput(input)) {
                handleOptionSelection(input);
                return;
            }

            // 重置待处理的工具调用
            pendingToolCalls.clear();

            // 添加用户消息到历史
            ChatMessage userMessage = new ChatMessage("user", input);
            history.add(userMessage);

            boolean useNative = context.getAppConfig().getAi() != null
                    && context.getAppConfig().getAi().isNativeToolCalling();

            if (useNative) {
                // 🔥 原生 function calling：多轮 agentic 循环
                runNativeToolLoop();
            } else {
                // 旧文本抓取路径：单轮 + 单工具
                context.getAiService().streamingChat(input, history, modelName);
                executePendingToolCall();
            }

            // 保存会话
            context.getSessionService().saveSession(sessionId, history);

        } catch (Exception e) {
            context.getUi().displayError("Error processing input: " + e.getMessage());
        } finally {
            // 结束性能监控
            monitor.stop();
        }
    }

    /**
     * 🔥 处理用户的选项选择
     */
    private void handleOptionSelection(String input) {
        // 将选项转换为实际命令
        String command = optionManager.processOptionSelection(input);

        if (command == null) {
            context.getUi().displayError("❌ 无效的选项，请输入 1-" + optionManager.getCurrentOptions().size());
            return;
        }

        // 显示用户选择的操作
        context.getUi().displayInfo("\n✅ 你选择了：" + command);
        context.getUi().displayInfo("正在执行...\n");

        // 将选择转换为新的用户请求，递归处理
        processInput(command);
    }

    private void handleMessage(ChatMessage message) {
        // 显示AI消息（用于流式输出的实时显示）
        context.getUi().displayAIMessage(message);

        // 🔥 尝试从 AI 响应中提取选项
        if (message.isAssistantMessage()) {
            boolean hasOptions = optionManager.extractOptionsFromResponse(message.getContent());

            if (hasOptions) {
                // AI 提供了选项，显示提示信息
                context.getUi().displayInfo("\n💡 请输入选项编号（1-" +
                    optionManager.getCurrentOptions().size() + "）来选择你想要的操作");
            }
        }

        // 注意：不在这里添加到历史记录
        // LangChainService 会在流式输出完成后，将完整的AI响应添加到历史记录
        // 这样可以避免历史记录中出现大量零散的 token 消息
    }

    // 用于缓存工具调用，等待 AI 响应完成后再执行（原生路径一轮可能有多个）
    private final List<ToolCall> pendingToolCalls = new ArrayList<>();

    private void handleToolCall(ToolCall toolCall) {
        // 🔥 缓存工具调用，不立即执行（等待 AI 流式输出完成）；原生路径一轮可累积多个
        this.pendingToolCalls.add(toolCall);
    }

    /**
     * 🔥 在 AI 响应完成后执行待处理的工具调用
     */
    public void executePendingToolCall() {
        if (pendingToolCalls.isEmpty()) {
            return;
        }

        ToolCall pendingToolCall = pendingToolCalls.get(0);

        try {
            // 非流式触发的工具调用，需要显示完整的确认框
            ToolExecution execution = new ToolExecution(
                pendingToolCall.getToolName(),
                pendingToolCall.getDescription() != null ? pendingToolCall.getDescription() : "执行工具操作",
                pendingToolCall.getParameters(),
                true
            );

            // 🔥 使用新的智能 3 选项确认系统
            ToolExecutionConfirmation.ActionType action = confirmation.askConfirmationWithOptions(execution);

            // 提取文件名用于总结
            String fileName = extractFileName(pendingToolCall);

            switch (action) {
                case CREATE_ONLY:
                    // 用户选择：执行操作
                    boolean success = executeToolCall(pendingToolCall);
                    // 🔥 只有执行成功时才显示成功总结
                    if (success) {
                        displayOperationSummary(pendingToolCall, action);
                    }
                    break;

                case CREATE_AND_RUN:
                    // 用户选择：执行并运行/查看详情
                    boolean executed = executeToolCall(pendingToolCall);

                    // 只有执行成功时才继续
                    if (executed) {
                        // 根据工具类型执行额外操作
                        if (pendingToolCall.getToolName().equals("write_file")) {
                            // 自动执行编译和运行
                            executeCompileAndRun(pendingToolCall);
                        } else {
                            // 其他工具：显示详情
                            displayOperationSummary(pendingToolCall, action);
                        }
                    }
                    break;

                case DISCARD:
                    // 用户选择：丢弃
                    context.getUi().displayWarning("⏭️  操作已取消");
                    // 🔥 根据工具类型显示取消总结
                    displayCancelSummary(pendingToolCall);
                    break;
            }
        } finally {
            // 清空待处理的工具调用
            pendingToolCalls.clear();
        }
    }

    /**
     * 🔥 原生 function calling 的多轮 agentic 循环：
     * 模型响应 → 执行本轮所有工具（仅写/执行类确认）→ 结果按 id 配对写回 → 无新输入再问模型，
     * 直到模型不再请求工具、或用户取消、或达到 maxToolIterations。
     */
    private void runNativeToolLoop() {
        AppConfig.AIConfig ai = context.getAppConfig().getAi();
        int maxIter = ai != null ? ai.getMaxToolIterations() : 10;
        boolean auto = ai == null || ai.isAutoProcessToolResults();
        int iter = 0;

        while (true) {
            pendingToolCalls.clear();
            // 一轮模型响应（无新用户输入；用户消息与历史已在 history 中）
            context.getAiService().streamingChat(null, history, modelName);

            if (pendingToolCalls.isEmpty()) {
                break; // 模型只产出文本 → 自然终止
            }

            List<ToolCall> batch = new ArrayList<>(pendingToolCalls);
            boolean stop = false;

            for (ToolCall call : batch) {
                if (stop) {
                    // 用户已取消：为剩余工具补 declined 结果，保持 call/result 配对
                    history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(),
                            "用户已取消后续工具执行。"));
                    continue;
                }

                // 仅写/执行类需要确认（除非处于自动批准模式）
                if (requiresConfirmation(call) && !confirmation.isAutoApproveMode()) {
                    ToolExecution exec = new ToolExecution(
                            call.getToolName(),
                            call.getDescription() != null ? call.getDescription() : "执行工具操作",
                            call.getParameters(),
                            true);
                    ToolExecutionConfirmation.ActionType action = confirmation.askConfirmationWithOptions(exec);
                    if (action == ToolExecutionConfirmation.ActionType.DISCARD) {
                        context.getUi().displayWarning("⏭️  已取消：" + describeTool(call));
                        history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(),
                                "用户拒绝执行该工具。"));
                        stop = true;
                        continue;
                    }
                }

                // 执行并把结果按 id 配对写回 history
                ToolResult result = toolDispatcher.dispatch(call);
                displayNativeToolResult(call, result);
                String resultText = result.isSuccess()
                        ? (result.getOutput() == null || result.getOutput().isBlank()
                            ? "执行成功（无输出）。" : result.getOutput())
                        : ("执行失败: " + result.getError());
                history.add(ChatMessage.toolResult(call.getProviderCallId(), call.getToolName(), resultText));
            }

            if (stop) {
                break;      // 用户 DISCARD → 停止循环，交回用户
            }
            if (!auto) {
                break;      // 不自动回喂结果 → 执行一批后停止
            }
            if (++iter >= maxIter) {
                history.add(new ChatMessage("system",
                        "已达到最大工具调用轮次(" + maxIter + ")，停止自动执行。"));
                context.getUi().displayWarning("⚠️  已达最大工具轮次(" + maxIter + ")，停止自动执行。");
                break;
            }
        }
    }

    /** 仅写/执行类工具需要确认；只读操作（file_manager read/list/info、grep_search）静默放行。 */
    private boolean requiresConfirmation(ToolCall call) {
        String name = call.getToolName();
        if (name == null) {
            return true;
        }
        switch (name) {
            case "file_manager": {
                Object cmd = call.getParameters() == null ? null : call.getParameters().get("command");
                String c = cmd == null ? "" : cmd.toString().toLowerCase();
                return c.equals("write") || c.equals("create") || c.equals("delete");
            }
            case "command_executor":
            case "code_executor":
                return true;
            case "grep_search":
                return false;
            default:
                return true; // MCP / 未知工具默认确认
        }
    }

    private String describeTool(ToolCall call) {
        String cmd = extractCommand(call);
        if (cmd != null) {
            return call.getToolName() + "(" + cmd + ")";
        }
        String fn = extractFileName(call);
        if (fn != null) {
            return call.getToolName() + "(" + fn + ")";
        }
        return call.getToolName();
    }

    /** 显示原生工具执行结果（沿用简洁风格）。 */
    private void displayNativeToolResult(ToolCall call, ToolResult result) {
        if (result.isSuccess()) {
            context.getUi().displaySuccess("✅ 完成: " + describeTool(call));
            String output = result.getOutput();
            if (output != null && !output.trim().isEmpty()) {
                for (String line : output.trim().split("\n")) {
                    context.getUi().getTerminal().writer().println("  " + line);
                }
                context.getUi().getTerminal().writer().flush();
            }
        } else {
            context.getUi().displayError("❌ 失败: " + result.getError());
        }
    }

    /**
     * 🔥 自动执行编译和运行
     */
    private void executeCompileAndRun(ToolCall toolCall) {
        try {
            // 从参数中提取文件名
            String fileName = extractFileName(toolCall);
            if (fileName == null) {
                context.getUi().displayWarning("⚠️  无法提取文件名，跳过自动运行");
                return;
            }

            context.getUi().displayInfo("\n🚀 正在自动编译和运行...\n");

            boolean compiled = false;
            boolean executed = false;

            // 根据文件类型执行不同的命令
            if (fileName.endsWith(".java")) {
                // Java 文件：编译 + 运行
                String className = fileName.replace(".java", "");

                // 1. 编译
                context.getUi().displayInfo("⏺ Bash(javac " + fileName + ")");
                executeCommand("javac " + fileName);
                compiled = true;

                // 2. 运行
                context.getUi().displayInfo("⏺ Bash(java " + className + ")");
                executeCommand("java " + className);
                executed = true;

            } else if (fileName.endsWith(".py")) {
                // Python 文件：直接运行
                context.getUi().displayInfo("⏺ Bash(python3 " + fileName + ")");
                executeCommand("python3 " + fileName);
                executed = true;

            } else if (fileName.endsWith(".sh")) {
                // Shell 脚本：添加执行权限 + 运行
                context.getUi().displayInfo("⏺ Bash(chmod +x " + fileName + " && ./" + fileName + ")");
                executeCommand("chmod +x " + fileName);
                executeCommand("./" + fileName);
                executed = true;

            } else {
                context.getUi().displayInfo("ℹ️  不支持自动运行此类型的文件：" + fileName);
            }

            // 🔥 生成总结
            displayCompletionSummary(fileName, compiled, executed);

        } catch (Exception e) {
            context.getUi().displayError("❌ 自动运行失败: " + e.getMessage());
        }
    }

    /**
     * 🔥 显示操作完成总结
     */
    private void displayCompletionSummary(String fileName, boolean compiled, boolean executed) {
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("⏺ 完成！我已经成功为你创建了程序：");
        context.getUi().getTerminal().writer().println();

        int step = 1;
        context.getUi().getTerminal().writer().println(step++ + ". 创建了 " + fileName + " 文件");

        if (compiled) {
            String classFile = fileName.replace(".java", ".class");
            context.getUi().getTerminal().writer().println(step++ + ". 编译了程序，生成了 " + classFile + " 文件");
        }

        if (executed) {
            context.getUi().getTerminal().writer().println(step++ + ". 运行了程序，输出结果如上所示");
        }

        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("程序已经可以正常工作了。你现在可以：");
        context.getUi().getTerminal().writer().println("- 查看源代码文件：" + fileName);
        context.getUi().getTerminal().writer().println("- 修改程序内容");
        context.getUi().getTerminal().writer().println("- 重新编译和运行");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("需要我帮你做其他什么吗？");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println(); // 🔥 额外的换行，确保 thought> 提示符另起一行
        context.getUi().getTerminal().writer().flush();
    }

    /**
     * 🔥 根据操作类型智能显示总结
     */
    private void displayOperationSummary(ToolCall toolCall, ToolExecutionConfirmation.ActionType action) {
        String toolName = toolCall.getToolName();

        context.getUi().getTerminal().writer().println();

        switch (toolName) {
            case "write_file" -> displayWriteFileSummary(toolCall, action);
            case "bash", "command_executor" -> displayCommandExecutionSummary(toolCall, action);
            case "edit_file" -> displayEditFileSummary(toolCall, action);
            case "read_file" -> displayReadFileSummary(toolCall, action);
            case "list_directory" -> displayListDirectorySummary(toolCall, action);
            default -> displayDefaultOperationSummary(toolCall, action);
        }

        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("需要我帮你做其他什么吗？");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println(); // 额外换行，让 thought> 另起一行
        context.getUi().getTerminal().writer().flush();
    }

    /**
     * 显示文件创建的总结
     */
    private void displayWriteFileSummary(ToolCall toolCall, ToolExecutionConfirmation.ActionType action) {
        String fileName = extractFileName(toolCall);

        context.getUi().getTerminal().writer().println("⏺ 完成！文件创建成功：");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("1. 创建了 " + fileName + " 文件");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("文件已保存到当前目录。接下来你可以：");
        context.getUi().getTerminal().writer().println("- 查看文件内容：cat " + fileName);
        context.getUi().getTerminal().writer().println("- 编辑文件：vim " + fileName + " 或使用你喜欢的编辑器");

        if (fileName.endsWith(".java")) {
            String className = fileName.replace(".java", "");
            context.getUi().getTerminal().writer().println("- 编译运行：javac " + fileName + " && java " + className);
        } else if (fileName.endsWith(".py")) {
            context.getUi().getTerminal().writer().println("- 运行程序：python3 " + fileName);
        } else if (fileName.endsWith(".sh")) {
            context.getUi().getTerminal().writer().println("- 运行脚本：chmod +x " + fileName + " && ./" + fileName);
        }
    }

    /**
     * 显示命令执行的总结
     */
    private void displayCommandExecutionSummary(ToolCall toolCall, ToolExecutionConfirmation.ActionType action) {
        String command = extractCommand(toolCall);

        context.getUi().getTerminal().writer().println("⏺ 完成！命令执行成功：");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("1. 执行了命令：" + command);
        context.getUi().getTerminal().writer().println();

        // 根据命令类型给出不同的建议
        if (command != null) {
            if (command.contains("rm ") || command.contains("delete")) {
                context.getUi().getTerminal().writer().println("文件/目录已删除。你可以：");
                context.getUi().getTerminal().writer().println("- 确认删除结果：ls -la");
                context.getUi().getTerminal().writer().println("- 如需恢复，可查看备份或使用版本控制");
            } else if (command.startsWith("git ")) {
                context.getUi().getTerminal().writer().println("Git 操作已完成。你可以：");
                context.getUi().getTerminal().writer().println("- 查看状态：git status");
                context.getUi().getTerminal().writer().println("- 查看日志：git log");
            } else if (command.startsWith("mvn ") || command.startsWith("gradle ")) {
                context.getUi().getTerminal().writer().println("构建操作已完成。你可以：");
                context.getUi().getTerminal().writer().println("- 查看构建结果");
                context.getUi().getTerminal().writer().println("- 运行生成的程序");
            } else {
                context.getUi().getTerminal().writer().println("命令执行结果如上所示。");
            }
        }
    }

    /**
     * 显示文件编辑的总结
     */
    private void displayEditFileSummary(ToolCall toolCall, ToolExecutionConfirmation.ActionType action) {
        String fileName = extractFileName(toolCall);

        context.getUi().getTerminal().writer().println("⏺ 完成！文件修改成功：");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("1. 修改了 " + fileName + " 文件");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("修改已保存。你可以：");
        context.getUi().getTerminal().writer().println("- 查看修改内容：cat " + fileName);
        context.getUi().getTerminal().writer().println("- 查看差异：git diff " + fileName);
    }

    /**
     * 显示文件读取的总结
     */
    private void displayReadFileSummary(ToolCall toolCall, ToolExecutionConfirmation.ActionType action) {
        String fileName = extractFileName(toolCall);

        context.getUi().getTerminal().writer().println("⏺ 完成！文件读取成功：");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("文件内容如上所示。");
    }

    /**
     * 显示目录列出的总结
     */
    private void displayListDirectorySummary(ToolCall toolCall, ToolExecutionConfirmation.ActionType action) {
        context.getUi().getTerminal().writer().println("⏺ 完成！目录内容如上所示。");
    }

    /**
     * 显示默认操作的总结
     */
    private void displayDefaultOperationSummary(ToolCall toolCall, ToolExecutionConfirmation.ActionType action) {
        context.getUi().getTerminal().writer().println("⏺ 完成！操作执行成功。");
    }

    /**
     * 🔥 根据工具类型显示取消总结
     */
    private void displayCancelSummary(ToolCall toolCall) {
        String toolName = toolCall.getToolName();

        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("⏺ 操作已取消");
        context.getUi().getTerminal().writer().println();

        switch (toolName) {
            case "write_file" -> {
                String fileName = extractFileName(toolCall);
                context.getUi().getTerminal().writer().println("我理解了，" + fileName + " 文件没有被创建。");
                context.getUi().getTerminal().writer().println();
                context.getUi().getTerminal().writer().println("如果你改变主意了，可以：");
                context.getUi().getTerminal().writer().println("- 重新告诉我创建这个文件");
                context.getUi().getTerminal().writer().println("- 或者让我创建其他文件");
            }
            case "bash", "command_executor" -> {
                String command = extractCommand(toolCall);
                context.getUi().getTerminal().writer().println("命令未执行：" + command);
                context.getUi().getTerminal().writer().println();
                context.getUi().getTerminal().writer().println("如需执行其他命令，请告诉我。");
            }
            case "edit_file" -> {
                String fileName = extractFileName(toolCall);
                context.getUi().getTerminal().writer().println("文件未修改：" + fileName);
                context.getUi().getTerminal().writer().println();
                context.getUi().getTerminal().writer().println("如需修改文件，请告诉我。");
            }
            default -> {
                context.getUi().getTerminal().writer().println("操作已取消。");
                context.getUi().getTerminal().writer().println();
                context.getUi().getTerminal().writer().println("如需帮助，请告诉我。");
            }
        }

        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println("需要我帮你做什么吗？");
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().println();
        context.getUi().getTerminal().writer().flush();
    }

    /**
     * 从工具调用中提取命令
     */
    private String extractCommand(ToolCall toolCall) {
        if (toolCall.getParameters() == null) {
            return null;
        }

        Object command = toolCall.getParameters().get("command");
        if (command != null) {
            return command.toString();
        }

        Object input = toolCall.getParameters().get("input");
        if (input != null) {
            return input.toString();
        }

        return null;
    }

    /**
     * 从工具调用中提取文件名
     */
    private String extractFileName(ToolCall toolCall) {
        if (toolCall.getParameters() == null) {
            return null;
        }

        Object path = toolCall.getParameters().get("path");
        if (path != null) {
            String pathStr = path.toString();
            // 提取文件名（去掉路径）
            int lastSlash = Math.max(pathStr.lastIndexOf('/'), pathStr.lastIndexOf('\\'));
            if (lastSlash >= 0 && lastSlash < pathStr.length() - 1) {
                return pathStr.substring(lastSlash + 1);
            }
            return pathStr;
        }

        return null;
    }

    /**
     * 执行命令（调用 command_executor 工具）
     */
    private void executeCommand(String command) {
        try {
            // 🔥 经由唯一收口执行，保证自动编译/运行也过沙箱检查点
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("command", command);
            ToolCall commandCall = new ToolCall("command_executor", params, null, false, 0);

            ToolResult result = toolDispatcher.dispatch(commandCall);

            if (result.isSuccess()) {
                // 显示命令输出
                String output = result.getOutput();
                if (output != null && !output.trim().isEmpty()) {
                    // 使用分行显示，确保输出清晰
                    String[] lines = output.trim().split("\n");
                    for (String line : lines) {
                        context.getUi().getTerminal().writer().println("  ⎿ " + line);
                    }
                    context.getUi().getTerminal().writer().flush();
                } else {
                    // 即使没有输出也显示成功信息
                    context.getUi().getTerminal().writer().println("  ⎿ (执行成功，无输出)");
                    context.getUi().getTerminal().writer().flush();
                }
            } else {
                context.getUi().displayError("  ⎿ 执行失败: " + result.getError());
            }
        } catch (Exception e) {
            context.getUi().displayError("  ⎿ 命令执行异常: " + e.getMessage());
            e.printStackTrace(); // 调试用
        }
    }

    /**
     * 🔥 实际执行工具调用
     * @return 是否执行成功
     */
    private boolean executeToolCall(ToolCall toolCall) {
        try {
            // 🔥 简化输出：只显示简短的执行提示
            // context.getUi().displayInfo("⚙️  正在执行: " + toolCall.getToolName() + "...");

            // 🔥 经由唯一收口执行（含 null 检查与未来的沙箱检查）
            ToolResult result = toolDispatcher.dispatch(toolCall);

            // 🔥 显示执行结果
            if (result.isSuccess()) {
                context.getUi().displaySuccess("✅ 完成");

                // 🔥 显示工具输出内容（如果有）
                String output = result.getOutput();
                if (output != null && !output.trim().isEmpty()) {
                    // 使用分行显示，确保输出清晰
                    String[] lines = output.trim().split("\n");
                    for (String line : lines) {
                        context.getUi().getTerminal().writer().println("  " + line);
                    }
                    context.getUi().getTerminal().writer().flush();
                }

                // 🔥 关键修复：将工具执行结果添加到历史记录中
                // 这样 AI 在下一轮对话中就能看到工具的执行结果
                ChatMessage toolResultMessage = new ChatMessage("system",
                    formatToolResultForHistory(toolCall, result));
                history.add(toolResultMessage);

                return true;
            } else {
                context.getUi().displayError("❌ 失败: " + result.getError());

                // 🔥 将错误信息也添加到历史
                ChatMessage errorMessage = new ChatMessage("system",
                    "Tool execution failed: " + result.getError());
                history.add(errorMessage);

                return false;
            }

        } catch (Exception e) {
            context.getUi().displayError("❌ 执行异常: " + e.getMessage());

            // 🔥 将异常也添加到历史
            ChatMessage exceptionMessage = new ChatMessage("system",
                "Tool execution exception: " + e.getMessage());
            history.add(exceptionMessage);

            // 调试时可以取消注释
            // e.printStackTrace();
            return false;
        }
    }

    /**
     * 🔥 格式化工具执行结果，用于添加到历史记录
     * 让 AI 能够理解工具的执行结果
     */
    private String formatToolResultForHistory(ToolCall toolCall, ToolResult result) {
        StringBuilder formatted = new StringBuilder();

        // 工具名称和参数
        formatted.append("Tool '").append(toolCall.getToolName()).append("' executed successfully");

        // 如果有参数，添加参数信息
        if (toolCall.getParameters() != null && !toolCall.getParameters().isEmpty()) {
            formatted.append(" with parameters: ");
            formatted.append(convertParametersToJson(toolCall.getParameters()));
        }

        formatted.append("\n");

        // 工具输出结果
        String output = result.getOutput();
        if (output != null && !output.trim().isEmpty()) {
            formatted.append("Result:\n");
            formatted.append(output);
        } else {
            formatted.append("Operation completed successfully.");
        }

        return formatted.toString();
    }

    /**
     * 将参数 Map 转换为 JSON 字符串
     */
    private String convertParametersToJson(java.util.Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }

        try {
            // 使用 Jackson 将参数转换为 JSON
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parameters);
        } catch (Exception e) {
            // 降级：简单的 JSON 拼接
            StringBuilder json = new StringBuilder("{");
            parameters.forEach((key, value) -> {
                json.append("\"").append(key).append("\":\"").append(value).append("\",");
            });
            if (json.length() > 1) {
                json.setLength(json.length() - 1);
            }
            json.append("}");
            return json.toString();
        }
    }

    /**
     * 🔥 设置自动批准模式（用于批量操作）
     */
    public void setAutoApprove(boolean enabled) {
        confirmation.setAutoApproveMode(enabled);
    }

    /**
     * 🔥 检查是否处于自动批准模式
     */
    public boolean isAutoApproveMode() {
        return confirmation.isAutoApproveMode();
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public String getSessionId() {
        return sessionId;
    }
}

