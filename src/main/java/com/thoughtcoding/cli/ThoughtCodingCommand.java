package com.thoughtcoding.cli;

import com.thoughtcoding.core.AgentLoop;
import com.thoughtcoding.core.DirectCommandExecutor;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.service.SessionService;
import com.thoughtcoding.ui.ThoughtCodingUI;
import com.thoughtcoding.config.MCPConfig;
import com.thoughtcoding.config.MCPServerConfig;
import com.thoughtcoding.cron.CronJob;
import com.thoughtcoding.team.TeamManager;
import picocli.CommandLine;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 命令解析和路由
 *
 * 参数解析：Picocli自动将 --interactive 等参数映射到字段
 *
 * 命令路由：根据参数组合决定执行路径
 *
 * 上下文传递：确保所有命令都能访问统一的上下文
 *
 * 服务获取和准备，通过Context统一获取服务实例 (Command类从Context获取服务)
 */
@CommandLine.Command(name = "thought", mixinStandardHelpOptions = true,
        description = "ThoughtCoding CLI - Interactive Code Assistant")
public class ThoughtCodingCommand implements Callable<Integer> {

    private final ThoughtCodingContext context;

    // 🔥 定时任务(cron)自主唤醒：所有 processInput 站点共用的一把锁，
    // 保证"用户手动输入的回合"与"定时到点自动回合"绝不同时跑（AgentLoop 的
    // history/pendingToolCalls 与 LangChainService 的全局 handler 均非并发安全）。
    private final ReentrantLock agentLock = new ReentrantLock();

    // 添加会话管理字段
    private AgentLoop currentAgentLoop;
    private String currentSessionId;

    // 直接命令执行器
    private DirectCommandExecutor directCommandExecutor;

    @CommandLine.Option(names = {"-i", "--interactive"}, description = "Run in interactive mode")
    private boolean interactive = true;

    @CommandLine.Option(names = {"-c", "--continue"}, description = "Continue last session")
    private boolean continueSession;

    @CommandLine.Option(names = {"-S", "--session"}, description = "Specify session ID")
    private String sessionId;

    @CommandLine.Option(names = {"-p", "--prompt"}, description = "Single prompt mode")
    private String prompt;

    @CommandLine.Option(names = {"-m", "--model"}, description = "Specify model to use")
    private String model;

    @CommandLine.Option(names = {"--list-sessions"}, description = "List all sessions")
    private boolean listSessions;

    @CommandLine.Option(names = {"--delete-session"}, description = "Delete specified session")
    private String deleteSessionId;

    // 🔥 新增 MCP 选项
    @CommandLine.Option(names = {"--mcp-tools"}, description = "Use predefined MCP tools (e.g., github-search,file-system,sql-query)")
    private String mcpTools;

    @CommandLine.Option(names = {"--mcp-connect"}, description = "Connect to custom MCP server")
    private String mcpConnect;

    @CommandLine.Option(names = {"--mcp-command"}, description = "MCP server command")
    private String mcpCommand;

    @CommandLine.Option(names = {"--mcp-list"}, description = "List available MCP tools and servers")
    private boolean mcpList;

    @CommandLine.Option(names = {"--mcp-predefined"}, description = "List predefined MCP tools")
    private boolean mcpPredefined;

    public ThoughtCodingCommand(ThoughtCodingContext context) {
        this.context = context;
        this.directCommandExecutor = new DirectCommandExecutor(context);
    }

    @Override
    public Integer call() {
        try {
            // 🔥 先处理 MCP 选项（在初始化上下文之前）
            handleMCPOptions();

            ThoughtCodingUI ui = context.getUi();
            SessionService sessionService = context.getSessionService();

            // 显示欢迎信息
            ui.showBanner();

            // 🔥 显示 MCP 状态信息
            if (context.isMCPEnabled() || mcpTools != null || mcpConnect != null) {
                int mcpToolCount = context.getMCPToolCount();
                if (mcpToolCount > 0) {
                    ui.displaySuccess("MCP Tools: " + mcpToolCount + " tools available");
                }
            }

            // 确定使用哪个模型
            String modelToUse = model != null ? model : context.getAppConfig().getDefaultModel();

            // 处理列表会话
            if (listSessions) {
                List<String> sessions = sessionService.listSessions();
                ui.displaySessionList(sessions);
                return 0;
            }

            // 处理删除会话
            if (deleteSessionId != null) {
                boolean deleted = sessionService.deleteSession(deleteSessionId);
                if (deleted) {
                    ui.displayInfo("Session deleted: " + deleteSessionId);
                } else {
                    ui.displayError("Failed to delete session: " + deleteSessionId);
                }
                return 0;
            }

            // 🔥 处理 MCP 列表命令
            if (mcpList) {
                context.printMCPInfo();
                return 0;
            }

            // 🔥 处理预定义 MCP 工具列表
            if (mcpPredefined) {
                showPredefinedMCPTools();
                return 0;
            }

            // 加载会话历史
            List<ChatMessage> history = new ArrayList<>();

            if (continueSession) {
                currentSessionId = sessionService.getLatestSessionId();
                if (currentSessionId != null) {
                    history = sessionService.loadSession(currentSessionId);
                    ui.displayInfo("Continuing from previous session: " + currentSessionId);
                } else {
                    ui.displayInfo("No previous session found. Starting new session.");
                    currentSessionId = UUID.randomUUID().toString();
                }
            } else if (sessionId != null) {
                currentSessionId = sessionId;
                history = sessionService.loadSession(sessionId);
                ui.displayInfo("Loaded session: " + sessionId);
            } else {
                // 创建新会话
                currentSessionId = UUID.randomUUID().toString();
                ui.displayInfo("Created new session: " + currentSessionId);
            }

            // 创建Agent循环
            // AgentLoop启动和协调 ，AI对话、工具调用、会话管理等
            /*流程协调：管理从用户输入到AI响应的完整流程

            工具调度：协调AI模型与工具系统的交互

            状态管理：维护对话状态和上下文

            错误处理：处理整个流程中的异常情况*/
            currentAgentLoop = new AgentLoop(context, currentSessionId, modelToUse);
            currentAgentLoop.loadHistory(history);

            // 单次对话模式
            if (prompt != null) {
                try {

                    // 显示用户输入
                    ChatMessage userMessage = new ChatMessage("user", prompt);
                    ui.displayUserMessage(userMessage);

                    // 处理AI响应，协调整个处理流程
                    agentLock.lock();
                    try {
                        currentAgentLoop.processInput(prompt);
                    } finally {
                        agentLock.unlock();
                    }

                    // 🔥 单次模式无唤醒消费者：等队友最多 waitMs 后主动 drain lead 收件箱
                    //（有界 drain-before-exit，避免后台守护线程被直接杀掉、结果丢失）。
                    TeamManager teamManager = context.getTeamManager();
                    if (teamManager != null) {
                        // 单次模式无唤醒消费者：等队友消息时留出 idleTimeoutSeconds 的空闲等待窗口，
                        // 让至少一轮计划审批握手（队友 submit_plan → lead review_plan）能走完，
                        // 再加 30s 兜底（后台守护线程被直接杀掉会丢结果）。
                        int idleSecs = Math.max(context.getAppConfig().getTeam().getIdleTimeoutSeconds(), 0);
                        long deadline = System.currentTimeMillis() + 30_000 + idleSecs * 1000L;
                        boolean sawMessage = false;
                        while (System.currentTimeMillis() < deadline) {
                            if (teamManager.leadHasMail()) {
                                List<com.thoughtcoding.team.TeamMessage> msgs = teamManager.drainLeadInbox();
                                if (!msgs.isEmpty()) {
                                    StringBuilder sb = new StringBuilder("<team_inbox>\n");
                                    for (com.thoughtcoding.team.TeamMessage m : msgs) {
                                        sb.append("<message from=\"").append(m.getFrom())
                                          .append("\" type=\"").append(m.getType()).append("\"");
                                        if (m.getRequestId() != null) {
                                            sb.append(" request_id=\"").append(m.getRequestId()).append("\"");
                                        }
                                        sb.append(">\n")
                                          .append(m.getContent()).append("\n</message>\n");
                                    }
                                    sb.append("</team_inbox>");
                                    ui.displayInfo("[team] 单次模式收到 " + msgs.size()
                                            + " 条队友消息，注入后续回合…");
                                    agentLock.lock();
                                    try {
                                        currentAgentLoop.processInput(sb.toString());
                                    } finally {
                                        agentLock.unlock();
                                    }
                                    sawMessage = true;
                                }
                            }
                            if (teamManager.active().stream().noneMatch(h -> !h.isFinished())) {
                                break; // 没有仍在跑的队友 → 不再等
                            }
                            Thread.sleep(500);
                        }
                        teamManager.shutdown();
                        if (sawMessage) {
                            ui.displayInfo("[team] 已收集队友结果，继续后续回合后退出。");
                        }
                    }

                    // 单次模式结束：关闭后台任务管理器
                    currentAgentLoop.shutdownBackground();

                    return 0;
                } catch (Exception e) {
                    context.getUi().displayError("Failed to process prompt: " + e.getMessage());
                    e.printStackTrace();
                    return 1;
                }
            }

            // Picocli自动解析命令行参数并注入到字段中
            // 交互式模式
            if (interactive) {
                return startInteractiveMode(currentAgentLoop, ui);
            }

            return 0;

        } catch (Exception e) {
            context.getUi().displayError("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 🔥 处理 MCP 选项
     */
    private void handleMCPOptions() {
        ThoughtCodingUI ui = context.getUi();

        if (mcpTools != null) {
            ui.displayInfo("Connecting predefined MCP tools: " + mcpTools);
            boolean success = context.usePredefinedMCPTools(mcpTools);
            if (success) {
                ui.displaySuccess("MCP tools connected successfully");
            } else {
                ui.displayError("Failed to connect MCP tools");
            }
        }

        if (mcpConnect != null && mcpCommand != null) {
            ui.displayInfo("Connecting MCP server: " + mcpConnect);
            boolean success = context.connectMCPServer(mcpConnect, mcpCommand, Collections.emptyList());
            if (success) {
                ui.displaySuccess("MCP server connected successfully");
            } else {
                ui.displayError("Failed to connect MCP server");
            }
        }
    }

    /**
     * 🔥 显示预定义 MCP 工具列表
     */
    private void showPredefinedMCPTools() {
        ThoughtCodingUI ui = context.getUi();
        ui.getTerminal().writer().println("\n🔧 Predefined MCP Tools:");
        ui.getTerminal().writer().println("──────────────────────");
        ui.getTerminal().writer().println("• github-search    - GitHub repository search");
        ui.getTerminal().writer().println("• sql-query        - PostgreSQL database queries");
        ui.getTerminal().writer().println("• file-system      - Local file system operations");
        ui.getTerminal().writer().println("• web-search       - Web search using Brave");
        ui.getTerminal().writer().println("• calculator       - Mathematical calculations");
        ui.getTerminal().writer().println("• weather          - Weather information");
        ui.getTerminal().writer().println("• memory           - Memory operations");
        ui.getTerminal().writer().println();
        ui.getTerminal().writer().println("Usage: --mcp-tools tool1,tool2,tool3");
        ui.getTerminal().writer().flush();
    }

    private Integer startInteractiveMode(AgentLoop agentLoop, ThoughtCodingUI ui) {
        ui.displayInfo("Entering interactive mode. Type 'exit' to quit, 'help' for commands.");

        // 🔥 定时任务(cron) + 团队(teammate)唤醒消费者：独立守护线程，到点任务/队友消息由
        // cronScheduler 或 MessageBus 入队、此线程持锁自动执行。
        // 主线程在 readLine 阻塞时【不】持锁 → tryLock 成功 → 自动回合照常跑；
        // 主线程正在跑一轮时持锁 → tryLock 失败 → 跳过本跳、下跳再试，绝不与用户回合并发。
        var cronScheduler = context.getCronScheduler();
        var teamManager = context.getTeamManager();
        Thread cronConsumer = null;
        if (cronScheduler != null || teamManager != null) {
            cronConsumer = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return; // 正常关停
                    }
                    if (!agentLock.tryLock()) {
                        continue; // 用户回合正在跑（或即将开始）→ 跳过
                    }
                    try {
                        // 空转不要碰 auto-approve：无到点 cron 且无队友消息时直接跳过，
                        // 否则每 200ms 的 setAutoApprove(true)+还原会不停打印"自动批准/交互确认"刷屏
                        // （回归自 cron+team 合并：原 cron 版把 setAutoApprove 放在 !jobs.isEmpty() 内部）。
                        boolean cronPending = cronScheduler != null && cronScheduler.hasFired();
                        boolean teamPending = teamManager != null && teamManager.leadHasMail();
                        if (!cronPending && !teamPending) {
                            continue;   // finally 里 unlock
                        }
                        // 定时任务本就无人值守：drain 期间自动放行工具，避免定时轮
                        // 在 UI LineReader 上弹确认、与主线程的输入 LineReader 抢读同一终端。
                        // 团队唤醒同样无人值守（队友消息自动到达），共用同一 auto-approve 窗口。
                        boolean prev = agentLoop.isAutoApproveMode();
                        agentLoop.setAutoApprove(true);
                        try {
                            // (a) cron 任务
                            if (cronPending) {
                                for (CronJob job : cronScheduler.drainFired()) {
                                    ui.displayInfo("[cron fired] " + job.getPrompt());
                                    agentLoop.processInput("[Scheduled] " + job.getPrompt());
                                }
                            }
                            // (b) 团队：把全部队友消息合并成一条 <team_inbox> user 消息，只唤醒一次。
                            //     带 request_id 的协议消息原样上浮，lead 据此 review_plan 等。
                            if (teamPending) {
                                List<com.thoughtcoding.team.TeamMessage> msgs = teamManager.drainLeadInbox();
                                if (!msgs.isEmpty()) {
                                    StringBuilder sb = new StringBuilder("<team_inbox>\n");
                                    for (com.thoughtcoding.team.TeamMessage m : msgs) {
                                        sb.append("<message from=\"").append(m.getFrom())
                                          .append("\" type=\"").append(m.getType()).append("\"");
                                        if (m.getRequestId() != null) {
                                            sb.append(" request_id=\"").append(m.getRequestId()).append("\"");
                                        }
                                        sb.append(">\n")
                                          .append(m.getContent()).append("\n</message>\n");
                                    }
                                    sb.append("</team_inbox>");
                                    ui.displayInfo("[team] " + msgs.size()
                                            + " 条来自队友的消息，正在唤醒主Agent处理…");
                                    agentLoop.processInput(sb.toString());
                                }
                            }
                        } finally {
                            agentLoop.setAutoApprove(prev);
                        }
                    } finally {
                        agentLock.unlock();
                    }
                }
            }, "wake-consumer");
            cronConsumer.setDaemon(true);
            cronConsumer.start();
        }

        while (true) {
            try {
                // 🔥 在读取输入前输出一个换行，确保 thought> 提示符在新的一行
                ui.getTerminal().writer().println();
                ui.getTerminal().writer().flush();

                String input = ui.readInput("thought> ");

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                String trimmedInput = input.trim();

                // 退出命令
                if (trimmedInput.equalsIgnoreCase("exit") || trimmedInput.equalsIgnoreCase("quit")) {
                    // 🔥 关停定时任务消费者 + 调度器 + 团队（守护线程即便漏网也不阻塞 JVM 退出）
                    if (cronConsumer != null) {
                        cronConsumer.interrupt();
                    }
                    if (cronScheduler != null) {
                        cronScheduler.shutdown();
                    }
                    if (teamManager != null) {
                        teamManager.shutdown();
                    }
                    // 关闭后台任务管理器（中断在跑任务）
                    agentLoop.shutdownBackground();
                    // 设置UI回调
                    ui.displayInfo("Goodbye!");
                    break;
                }

                // 帮助命令
                if (trimmedInput.equalsIgnoreCase("help")) {
                    showHelp();
                    continue;
                }

                // 清屏命令
                if (trimmedInput.equalsIgnoreCase("clear")) {
                    ui.clearScreen();
                    continue;
                }

                // 🛑 停止生成命令
                if (trimmedInput.equalsIgnoreCase("stop") || trimmedInput.equalsIgnoreCase("停止")) {
                    stopCurrentGeneration();
                    continue;
                }

                // 🔧 直接命令帮助
                if (trimmedInput.equalsIgnoreCase("/commands") || trimmedInput.equalsIgnoreCase("/cmds")) {
                    directCommandExecutor.listSupportedCommands();
                    continue;
                }

                // 🔥 MCP 相关命令 - 直接在这里处理
                if (trimmedInput.startsWith("/mcp")) {
                    handleMCPCommand(trimmedInput);
                    continue;
                }

                // 🚀 新增：检查是否是直接命令执行
                //TODO ：优化正则检测，降低误判率
                if (directCommandExecutor.shouldExecuteDirectly(trimmedInput)) {
                    directCommandExecutor.executeDirectCommand(trimmedInput);
                    continue;
                }

                // 🚨 关键修复：检查是否是参数格式
                if (isParameterFormat(trimmedInput)) {
                    handleParameterInInteractiveMode(trimmedInput, agentLoop);
                    continue;
                }

                // 处理普通对话
                agentLock.lock();
                try {
                    agentLoop.processInput(trimmedInput);
                } finally {
                    agentLock.unlock();
                }

            } catch (Exception e) {
                ui.displayError("Error: " + e.getMessage());
            }
        }

        return 0;
    }

// 删除 handleInternalCommand 方法，因为我们已经直接处理了 MCP 命令
// 删除 handleSinglePrompt 方法，因为单次提示模式已经在 call() 方法中处理了

    /**
     * 🔥 处理 MCP 相关命令
     */
    private void handleMCPCommand(String command) {
        String[] parts = command.substring(4).trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1] : "";

        ThoughtCodingUI ui = context.getUi();

        switch (cmd) {
            case "list":
                context.printMCPInfo();
                break;
            case "connect":
                if (!argument.isEmpty()) {
                    String[] connectArgs = argument.split("\\s+");
                    if (connectArgs.length >= 1) {
                        String serverName = connectArgs[0];
                        String serverCommand = connectArgs.length >= 2 ? connectArgs[1] : "npx";

                        // 🔥 根据服务器名自动构建参数
                        List<String> args = buildMCPArgs(serverName);

                        boolean success = context.connectMCPServer(serverName, serverCommand, args);
                        if (success) {
                            ui.displaySuccess("MCP server connected: " + serverName);
                        } else {
                            ui.displayError("Failed to connect MCP server");
                        }
                    } else {
                        ui.displayError("Usage: /mcp connect <server-name> [command]");
                        ui.displayInfo("Example: /mcp connect filesystem");
                        ui.displayInfo("         /mcp connect filesystem npx");
                    }
                } else {
                    ui.displayError("Usage: /mcp connect <server-name> [command]");
                }
                break;
            case "tools":
                if (!argument.isEmpty()) {
                    boolean success = context.usePredefinedMCPTools(argument);
                    if (success) {
                        ui.displaySuccess("MCP tools connected: " + argument);
                    } else {
                        ui.displayError("Failed to connect MCP tools");
                    }
                } else {
                    ui.displayError("Usage: /mcp tools <tool1,tool2,tool3>");
                }
                break;
            case "disconnect":
                if (!argument.isEmpty()) {
                    context.disconnectMCPServer(argument);
                    ui.displaySuccess("MCP server disconnected: " + argument);
                } else {
                    ui.displayError("Usage: /mcp disconnect <server-name>");
                }
                break;
            case "predefined":
                showPredefinedMCPTools();
                break;
            default:
                ui.displayError("Unknown MCP command: " + cmd);
                ui.displayInfo("Available MCP commands:");
                ui.displayInfo("  /mcp list                    - List connected MCP tools");
                ui.displayInfo("  /mcp connect <name> <cmd>    - Connect to MCP server");
                ui.displayInfo("  /mcp tools <tool1,tool2>     - Use predefined tools");
                ui.displayInfo("  /mcp disconnect <name>       - Disconnect MCP server");
                ui.displayInfo("  /mcp predefined              - Show predefined tools");
                break;
        }
    }

    /**
     * 检查输入是否是参数格式（以 - 或 -- 开头）
     */
    private boolean isParameterFormat(String input) {
        return input.startsWith("-") && input.length() > 1;
    }

    /**
     * 在交互模式中处理参数命令
     */
    private void handleParameterInInteractiveMode(String parameter, AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        String[] args = parameter.split("\\s+");

        switch (args[0]) {
            case "-i":
            case "--interactive":
                ui.displayInfo("Already in interactive mode");
                break;

            case "-c":
            case "--continue":
                handleContinueInInteractive(currentAgentLoop);
                break;

            case "-S":
            case "--session":
                if (args.length > 1) {
                    handleLoadSession(args[1], currentAgentLoop);
                } else {
                    ui.displayError("Usage: -S <session-id>");
                }
                break;

            case "-p":
            case "--prompt":
                if (args.length > 1) {
                    // 重新组合提示内容（处理带空格的提示）
                    String prompt = parameter.substring(args[0].length()).trim();

                    // 🚨 关键修复：去掉引号
                    if (prompt.startsWith("\"") && prompt.endsWith("\"")) {
                        prompt = prompt.substring(1, prompt.length() - 1);
                    }

                    if (!prompt.isEmpty()) {
                        handleSinglePromptInInteractive(prompt, currentAgentLoop);
                    } else {
                        ui.displayError("Usage: -p \"your prompt\"");
                    }
                } else {
                    ui.displayError("Usage: -p \"your prompt\"");
                }
                break;

            // 🔥 新增 MCP 参数处理
            case "--mcp-tools":
                if (args.length > 1) {
                    boolean success = context.usePredefinedMCPTools(args[1]);
                    if (success) {
                        ui.displaySuccess("MCP tools connected: " + args[1]);
                    } else {
                        ui.displayError("Failed to connect MCP tools");
                    }
                } else {
                    ui.displayError("Usage: --mcp-tools <tool1,tool2,tool3>");
                }
                break;

            case "--mcp-list":
                context.printMCPInfo();
                break;

            case "--mcp-predefined":
                showPredefinedMCPTools();
                break;

            default:
                ui.displayError("Unknown parameter: " + args[0]);
                break;
        }
    }

    /**
     * 在交互模式中继续上次会话
     */
    private void handleContinueInInteractive(AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            //Command从Context获取服务
            String latestSessionId = context.getSessionService().getLatestSessionId();
            if (latestSessionId != null) {
                List<ChatMessage> history = context.getSessionService().loadSession(latestSessionId);

                // 🚨 关键修复：过滤空消息
                List<ChatMessage> filteredHistory = history.stream()
                        .filter(msg -> msg != null &&
                                msg.getContent() != null &&
                                !msg.getContent().trim().isEmpty())
                        .collect(Collectors.toList());

                currentAgentLoop.loadHistory(filteredHistory);
                currentSessionId = latestSessionId;
                ui.displaySuccess("Continued from session: " + latestSessionId);

                // 显示加载的消息数量
                ui.displayInfo("Loaded " + filteredHistory.size() + " messages from history");
            } else {
                ui.displayInfo("No previous session found.");
            }
        } catch (Exception e) {
            ui.displayError("Failed to continue session: " + e.getMessage());
        }
    }

    /**
     * 在交互模式中加载指定会话
     */
    private void handleLoadSession(String sessionId, AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            List<ChatMessage> history = context.getSessionService().loadSession(sessionId);
            currentAgentLoop.loadHistory(history);
            currentSessionId = sessionId;
            ui.displaySuccess("Loaded session: " + sessionId);
        } catch (Exception e) {
            ui.displayError("Failed to load session: " + e.getMessage());
        }
    }

    /**
     * 在交互模式中执行单次提示
     */
    private void handleSinglePromptInInteractive(String prompt, AgentLoop agentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            ui.displayUserMessage(new ChatMessage("user", prompt));
            agentLock.lock();
            try {
                agentLoop.processInput(prompt);
            } finally {
                agentLock.unlock();
            }
        } catch (Exception e) {
            ui.displayError("Failed to process prompt: " + e.getMessage());
        }
    }

    /**
     * 🛑 停止当前的 AI 生成
     */
    private void stopCurrentGeneration() {
        ThoughtCodingUI ui = context.getUi();
        try {
            // 尝试停止 LangChainService 的生成
            if (context.getAiService() instanceof com.thoughtcoding.service.LangChainService) {
                com.thoughtcoding.service.LangChainService langChainService =
                    (com.thoughtcoding.service.LangChainService) context.getAiService();

                if (langChainService.isGenerating()) {
                    langChainService.stopCurrentGeneration();
                    ui.displayWarning("⏸️  生成已停止");
                } else {
                    ui.displayInfo("ℹ️  当前没有正在进行的生成");
                }
            } else {
                ui.displayWarning("⚠️  当前 AI 服务不支持停止功能");
            }
        } catch (Exception e) {
            ui.displayError("停止生成时出错: " + e.getMessage());
        }
    }

    private void showHelp() {
        context.getUi().displayInfo("""
                        🚀 可用命令：
                                                               \s
                                                                💬 对话命令：
                                                                  <消息>         发送消息给AI助手
                                                                  stop / 停止   停止当前的AI生成
                                                                  /new          开始新会话
                                                                  /save <名称>  保存当前会话
                                                                  /list         查看所有会话
                                                                  /clear        清空屏幕
                                                                  /help         显示帮助信息
                                                               \s
                                                                🔧 直接命令：
                                                                  java version  直接执行Java命令
                                                                  git status    直接执行Git命令
                                                                  pwd, ls, etc. 系统命令直接执行
                                                                  /commands     查看所有支持直接执行的命令
                                                               \s
                                                                🔧 MCP 命令：
                                                                  /mcp list             列出MCP工具
                                                                  /mcp connect <n> <c>  连接MCP服务器
                                                                  /mcp tools <t1,t2>    使用预定义工具
                                                                  /mcp disconnect <n>   断开MCP服务器
                                                                  /mcp predefined       显示预定义工具
                                                               \s
                                                                ⚡ 快捷命令：
                                                                  -c, --continue       继续上次会话
                                                                  -S <id>             加载指定会话 \s
                                                                  -p "提示"           单次提问模式
                                                                  --list-sessions     列出所有会话
                                                                  --mcp-tools <t>     使用MCP工具
                                                                  --mcp-list          列出MCP状态
                                                               \s
                                                                ❌ 退出命令：
                                                                  exit / quit         退出程序
                """);
    }

    /**
     * 🔥 根据服务器名从配置文件读取 MCP 参数
     * 优先使用配置文件中的配置，如果没有则使用默认配置
     */
    private List<String> buildMCPArgs(String serverName) {
        // 🔥 优先从配置文件读取
        var mcpConfig = context.getMcpConfig();
        var serverConfig = mcpConfig.getServerConfig(serverName);

        if (serverConfig != null && serverConfig.getArgs() != null && !serverConfig.getArgs().isEmpty()) {
            // 使用配置文件中的参数
            context.getUi().displayInfo("✓ 使用配置文件中的 " + serverName + " 配置");
            return new ArrayList<>(serverConfig.getArgs());
        }

        // 🔥 如果配置文件中没有，使用默认配置（向后兼容）
        context.getUi().displayWarning("⚠ 配置文件中未找到 " + serverName + " 配置，使用默认配置");

        List<String> args = new ArrayList<>();

        switch (serverName.toLowerCase()) {
            case "filesystem":
            case "file-system":
                args.add("-y");
                args.add("@modelcontextprotocol/server-filesystem");
                args.add(System.getProperty("user.home")); // 用户主目录
                break;

            case "sqlite":
                args.add("-y");
                args.add("@modelcontextprotocol/server-sqlite");
                args.add("--database");
                args.add("./data.db");
                break;

            case "postgres":
            case "postgresql":
                args.add("-y");
                args.add("@modelcontextprotocol/server-postgres");
                args.add("--connectionString");
                args.add("postgresql://user:pass@localhost:5432/db");
                break;

            case "github":
                args.add("-y");
                args.add("@modelcontextprotocol/server-github");
                args.add("--token");
                args.add("your_github_token_here");  // 占位符
                break;

            case "mysql":
                args.add("-y");
                args.add("@modelcontextprotocol/server-mysql");
                args.add("--connectionString");
                args.add("mysql://user:pass@localhost:3306/db");
                break;

            case "weather":
                args.add("-y");
                args.add("@coding-squirrel/mcp-weather-server");
                args.add("--apiKey");
                args.add("your_weather_api_key");
                break;

            default:
                // 如果是未知服务器，尝试作为包名直接安装
                args.add("-y");
                args.add(serverName);
                break;
        }

        return args;
    }

    // ... 其他方法保持不变（handleInternalCommand, handleSinglePrompt, handleNewSession, handleSaveSession, handleListSessions, displaySessionList, handleClearScreen, saveCurrentSession）
    // 这些方法不需要修改，保持原有逻辑
}