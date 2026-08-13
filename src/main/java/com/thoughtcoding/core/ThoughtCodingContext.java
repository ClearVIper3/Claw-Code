package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.config.ConfigManager;
import com.thoughtcoding.config.MCPConfig;
import com.thoughtcoding.mcp.MCPService;
import com.thoughtcoding.mcp.MCPToolManager;
import com.thoughtcoding.memory.MemoryService;
import com.thoughtcoding.memory.MemoryStore;
import com.thoughtcoding.cron.CronScheduler;
import com.thoughtcoding.cron.CronStore;
import com.thoughtcoding.cron.tools.CronCancelTool;
import com.thoughtcoding.cron.tools.CronListTool;
import com.thoughtcoding.cron.tools.CronScheduleTool;
import com.thoughtcoding.service.AIService;
import com.thoughtcoding.service.ContextManager;
import com.thoughtcoding.service.LangChainService;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.service.SessionService;
import com.thoughtcoding.tool.*;
import com.thoughtcoding.tool.tools.BashTool;
import com.thoughtcoding.tool.tools.EditTool;
import com.thoughtcoding.tool.tools.GlobTool;
import com.thoughtcoding.tool.tools.ReadTool;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.skill.SkillRegistry;
import com.thoughtcoding.task.TaskStore;
import com.thoughtcoding.task.tools.TaskClaimTool;
import com.thoughtcoding.task.tools.TaskCompleteTool;
import com.thoughtcoding.task.tools.TaskCreateTool;
import com.thoughtcoding.task.tools.TaskGetTool;
import com.thoughtcoding.task.tools.TaskListTool;
import com.thoughtcoding.task.tools.TaskUpdateTool;
import com.thoughtcoding.team.TeamManager;
import com.thoughtcoding.team.tools.CheckInboxTool;
import com.thoughtcoding.team.tools.RequestPlanTool;
import com.thoughtcoding.team.tools.RequestShutdownTool;
import com.thoughtcoding.team.tools.ReviewPlanTool;
import com.thoughtcoding.team.tools.SendMessageTool;
import com.thoughtcoding.team.tools.SpawnTeammateTool;
import com.thoughtcoding.team.tools.SubmitPlanTool;
import com.thoughtcoding.tool.tools.SkillTool;
import com.thoughtcoding.tool.tools.WriteTool;
import com.thoughtcoding.ui.ThoughtCodingUI;
import com.thoughtcoding.worktree.GitWorktreeManager;
import com.thoughtcoding.worktree.tools.CreateWorktreeTool;
import com.thoughtcoding.worktree.tools.KeepWorktreeTool;
import com.thoughtcoding.worktree.tools.RemoveWorktreeTool;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文初始化过程
 *
 * 依赖注入：确保各个组件都能获取到它们需要的依赖
 *
 * 生命周期管理：控制初始化顺序，避免循环依赖
 *
 * 资源配置：建立数据库连接、网络连接、文件句柄等
 */
public class ThoughtCodingContext {
    private final AppConfig appConfig;
    private final MCPConfig mcpConfig;
    private final AIService aiService;
    private final SessionService sessionService;
    private final ToolRegistry toolRegistry;
    private final ThoughtCodingUI ui;
    private final PerformanceMonitor performanceMonitor;

    // 🔥 新增 MCP 相关服务
    private final MCPService mcpService;
    private final MCPToolManager mcpToolManager;

    // 🔥 新增上下文管理器
    private final ContextManager contextManager;

    // 🔥 新增记忆系统（LLM 驱动：召回/储存/整理；非工具）
    private final MemoryService memoryService;

    // 🔥 新增定时任务(cron)调度器（可为 null = 定时任务系统关闭）
    private final CronScheduler cronScheduler;

    // 🔥 新增团队(Agent Teams)管理器（可为 null = 团队系统关闭）
    private final TeamManager teamManager;

    private ThoughtCodingContext(Builder builder) {
        this.appConfig = builder.appConfig;
        this.mcpConfig = builder.mcpConfig;
        this.aiService = builder.aiService;
        this.sessionService = builder.sessionService;
        this.toolRegistry = builder.toolRegistry;
        this.ui = builder.ui;
        this.performanceMonitor = builder.performanceMonitor;
        this.mcpService = builder.mcpService;
        this.mcpToolManager = builder.mcpToolManager;
        this.contextManager = builder.contextManager;
        this.memoryService = builder.memoryService;
        this.cronScheduler = builder.cronScheduler;
        this.teamManager = builder.teamManager;
    }

    public static ThoughtCodingContext initialize() {
        // 分层初始化，确保依赖顺序正确

        // 初始化配置管理器
        ConfigManager configManager = ConfigManager.getInstance();
        configManager.initialize("config.yaml");
        AppConfig appConfig = configManager.getAppConfig();
        MCPConfig mcpConfig = configManager.getMCPConfig();

        // 初始化沙箱（以启动时的工作目录为 workspace 根）
        Sandbox.init(System.getProperty("user.dir"));

        // 启动时扫描一次 skills/ 目录（name+description 常驻 system prompt，正文按需加载）
        SkillRegistry skillRegistry = SkillRegistry.scan(
                java.nio.file.Paths.get(System.getProperty("user.dir"), "skills"));

        // 能力层初始化,创建工具注册表
        ToolRegistry toolRegistry = new ToolRegistry(appConfig);

        // 🔥 创建 MCP 服务
        MCPService mcpService = new MCPService();
        MCPToolManager mcpToolManager = new MCPToolManager(mcpService, mcpConfig);

        // 注册内置工具 - 传递整个 AppConfig 对象
        if (appConfig.getTools().getBash().isEnabled()) {
            toolRegistry.register(new BashTool(appConfig));
        }

        if (appConfig.getTools().getRead().isEnabled()) {
            toolRegistry.register(new ReadTool(appConfig));
        }

        if (appConfig.getTools().getWrite().isEnabled()) {
            toolRegistry.register(new WriteTool(appConfig));
        }

        if (appConfig.getTools().getEdit().isEnabled()) {
            toolRegistry.register(new EditTool(appConfig));
        }

        if (appConfig.getTools().getGlob().isEnabled()) {
            toolRegistry.register(new GlobTool(appConfig));
        }

        // 技能加载工具：目录为空则不注册，不给模型一个永远查不到东西的工具
        if (!skillRegistry.isEmpty()) {
            toolRegistry.register(new SkillTool(skillRegistry));
        }

        // ── 任务系统（确定性 CRUD，落盘 .tasks/，无 LLM 层）：tasks.enabled=false 或初始化失败则整体为 null ──
        AppConfig.TaskConfig taskCfg = appConfig.getTasks();
        TaskStore taskStore = null;
        if (taskCfg != null && taskCfg.isEnabled()) {
            try {
                taskStore = TaskStore.load(
                        java.nio.file.Paths.get(System.getProperty("user.dir"), ".tasks"));
                toolRegistry.register(new TaskCreateTool(taskStore));
                toolRegistry.register(new TaskListTool(taskStore));
                toolRegistry.register(new TaskGetTool(taskStore));
                toolRegistry.register(new TaskUpdateTool(taskStore));
                toolRegistry.register(new TaskClaimTool(taskStore));
                toolRegistry.register(new TaskCompleteTool(taskStore));
            } catch (Exception e) {
                System.err.println("❌ 任务系统初始化失败: " + e.getMessage());
                e.printStackTrace();
                taskStore = null;
            }
        }

        // ── 工作树(git worktree)系统（s18 Worktree Isolation）：worktree.enabled=false 或 git 不可用
        //    或任务系统关闭则整体为 null（不注册工具，优雅降级）。仅构造管理器；工具在 build 后随
        //    团队工具一同注册（需要 taskStore 绑定 + 仅 lead 可用）。
        AppConfig.WorktreeConfig worktreeCfg = appConfig.getWorktree();
        GitWorktreeManager worktreeManager = null;
        if (worktreeCfg != null && worktreeCfg.isEnabled() && taskStore != null) {
            try {
                if (GitWorktreeManager.gitAvailable()) {
                    worktreeManager = new GitWorktreeManager(
                            java.nio.file.Paths.get(System.getProperty("user.dir")),
                            worktreeCfg.getBaseDir());
                }
            } catch (Exception e) {
                worktreeManager = null;
            }
        }

        // ── 定时任务(cron)系统（确定性调度 + 落盘 .scheduled_tasks.json，无 LLM 层）：
        //    cron.enabled=false 或初始化失败则整体为 null。轮询线程在此启动（生产者），
        //    消费者守护线程在 REPL 交互模式启动（见 ThoughtCodingCommand）。
        AppConfig.CronConfig cronCfg = appConfig.getCron();
        CronStore cronStore = null;
        CronScheduler cronScheduler = null;
        if (cronCfg != null && cronCfg.isEnabled()) {
            try {
                // 必须用绝对路径：FileUtils.writeFile 会对 getParent() 建目录，裸文件名会 NPE
                cronStore = CronStore.load(
                        java.nio.file.Paths.get(System.getProperty("user.dir"), ".scheduled_tasks.json"));
                cronScheduler = new CronScheduler(cronStore);
                cronScheduler.start();
                toolRegistry.register(new CronScheduleTool(cronStore));
                toolRegistry.register(new CronListTool(cronStore));
                toolRegistry.register(new CronCancelTool(cronStore, cronScheduler));
            } catch (Exception e) {
                System.err.println("❌ 定时任务系统初始化失败: " + e.getMessage());
                e.printStackTrace();
                cronStore = null;
                cronScheduler = null;
            }
        }

        // ── 团队(Agent Teams)系统：替换已移除的同步 subAgent，委派任务唯一方式是 spawn_teammate。
        //    team.enabled=false 或初始化失败则整体为 null。消息总线 + 队友注册表在此创建，
        //    唤醒消费者守护线程在 REPL 交互模式启动（见 ThoughtCodingCommand）。
        AppConfig.TeamConfig teamCfg = appConfig.getTeam();
        TeamManager teamManager = null;
        if (teamCfg != null && teamCfg.isEnabled()) {
            try {
                teamManager = new TeamManager(teamCfg.getMaxTeammates(), teamCfg.getMaxRounds(),
                        teamCfg.getIdleTimeoutSeconds(), teamCfg.isAutoClaim(), teamCfg.getIdlePollIntervalMs());
            } catch (Exception e) {
                System.err.println("❌ 团队系统初始化失败: " + e.getMessage());
                e.printStackTrace();
                teamManager = null;
            }
        }

        // 🔥 初始化 MCP 服务（如果启用）
        if (mcpConfig != null && mcpConfig.isEnabled()) {
            initializeMCPTools(mcpConfig, mcpService, toolRegistry);
        }

        // 服务层初始化
        // ── 记忆系统（非工具）：LLM 驱动召回/储存/整理；memory.enabled=false 或内存分配失败则整体为 null ──
        AppConfig.MemoryConfig memCfg = appConfig.getMemory();
        MemoryStore memoryStore = null;
        MemoryService memoryService = null;
        if (memCfg != null && memCfg.isEnabled()) {
            try {
                memoryStore = MemoryStore.load(
                        java.nio.file.Paths.get(System.getProperty("user.dir"), ".memory"),
                        memCfg.getMaxIndexEntries());
                memoryService = new MemoryService(appConfig, memoryStore, memCfg);
            } catch (Exception e) {
                // 记忆系统初始化失败不阻塞主对话
                memoryStore = null;
                memoryService = null;
            }
        }
        ContextManager contextManager = new ContextManager(appConfig, skillRegistry, memoryStore, taskStore);  // 🔥 创建上下文管理器
        AIService aiService = new LangChainService(appConfig, toolRegistry, contextManager);  // 🔥 注入 contextManager
        SessionService sessionService = new SessionService();
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();

        // UI层初始化
        ThoughtCodingUI ui = new ThoughtCodingUI();

        // 构建上下文（核心层初始化）
        ThoughtCodingContext context = new Builder()
                .appConfig(appConfig)
                .mcpConfig(mcpConfig)
                .aiService(aiService)
                .sessionService(sessionService)
                .toolRegistry(toolRegistry)
                .ui(ui)
                .performanceMonitor(performanceMonitor)
                .mcpService(mcpService)
                .mcpToolManager(mcpToolManager)
                .contextManager(contextManager)  // 🔥 添加 contextManager
                .memoryService(memoryService)   // 🔥 添加 memoryService（可为 null = 记忆关闭）
                .cronScheduler(cronScheduler)   // 🔥 添加 cronScheduler（可为 null = 定时任务关闭）
                .teamManager(teamManager)       // 🔥 添加 teamManager（可为 null = 团队关闭）
                .build();

        // 🔥 团队工具（spawn_teammate/send_message/check_inbox + s16 协议 request_shutdown/request_plan/
        // review_plan/submit_plan）：需持有已构建好的 context 来访问 teamManager（派生后台队友循环、
        // 登记协议 pending），故在 build 之后注册（对齐原 SubAgentTool 的惯例）。
        // teamManager 也在此后绑定 context（队友要复用 aiService/toolRegistry/contextManager）。
        if (context.getTeamManager() != null) {
            context.getTeamManager().setContext(context);
            context.getToolRegistry().register(new SpawnTeammateTool(context));
            context.getToolRegistry().register(new SendMessageTool(context));
            context.getToolRegistry().register(new CheckInboxTool(context));
            context.getToolRegistry().register(new RequestShutdownTool(context));
            context.getToolRegistry().register(new RequestPlanTool(context));
            context.getToolRegistry().register(new ReviewPlanTool(context));
            context.getToolRegistry().register(new SubmitPlanTool(context));

            // ── worktree 隔离（s18）Lead 侧工具：仅当功能开启+git 可用+任务系统在线时注册。
            //    已从队友可见规格中过滤（Teammate.TEAMMATE_EXCLUDED_TOOLS），仅 lead 可用。
            if (worktreeManager != null) {
                context.getToolRegistry().register(new CreateWorktreeTool(worktreeManager, taskStore));
                context.getToolRegistry().register(new RemoveWorktreeTool(worktreeManager));
                context.getToolRegistry().register(new KeepWorktreeTool(worktreeManager));
            }
        }

        return context;
    }

    /**
     * 🔥 初始化 MCP 工具
     */
    public static void initializeMCPTools(MCPConfig mcpConfig, MCPService mcpService, ToolRegistry toolRegistry) {
        // 🔥 简化输出：只在最后显示汇总信息

        if (mcpConfig != null && mcpConfig.isEnabled()) {
            int totalTools = 0;
            int successServers = 0;
            List<String> connectedServers = new ArrayList<>();

            for (var serverConfig : mcpConfig.getServers()) {
                if (serverConfig.isEnabled()) {
                    try {
                        // 静默连接，不输出中间过程
                        var tools = mcpService.connectToServer(
                                serverConfig.getName(),
                                serverConfig.getCommand(),
                                serverConfig.getArgs()
                        );

                        if (!tools.isEmpty()) {
                            // 注册工具（静默）
                            for (var tool : tools) {
                                toolRegistry.register(tool);
                            }
                            totalTools += tools.size();
                            successServers++;
                            connectedServers.add(serverConfig.getName());
                        }
                    } catch (Exception e) {
                        System.err.println("❌ 无法连接到 " + serverConfig.getName() + ": " + e.getMessage());
                    }
                }
            }

            // 🔥 输出汇总信息，包含已连接的 MCP 工具名称
            if (successServers > 0) {
                System.out.println("✅ 已加载 " + totalTools + " 个工具，已连接 MCP: " + String.join(", ", connectedServers));
            }
        }
    }

    /**
     * 🔥 动态连接 MCP 服务器（用于命令行调用）
     */
    public boolean connectMCPServer(String serverName, String command, List<String> args) {
        if (mcpService == null) {
            System.err.println("MCP 服务未初始化");
            return false;
        }

        try {
            // 🔥 直接传递三个参数，不再创建 Map
            var tools = mcpService.connectToServer(serverName, command, args);
            if (!tools.isEmpty()) {
                // 注册工具（静默）
                for (var tool : tools) {
                    toolRegistry.register(tool);
                }
                System.out.println("✓ 成功连接 MCP 服务器: " + serverName +
                        " (" + tools.size() + " 个工具)");
                return true;
            }
        } catch (Exception e) {
            System.err.println("✗ 连接 MCP 服务器失败: " + serverName + " - " + e.getMessage());
        }
        return false;
    }



    /**
     * 🔥 使用预定义 MCP 工具
     */
    public boolean usePredefinedMCPTools(String toolsList) {
        if (mcpToolManager == null) {
            System.err.println("MCP 工具管理器未初始化");
            return false;
        }

        try {
            var toolNames = java.util.Arrays.asList(toolsList.split(","));
            var tools = mcpToolManager.connectPredefinedTools(toolNames);
            if (!tools.isEmpty()) {
                // 注册工具（静默）
                for (var tool : tools) {
                    toolRegistry.register(tool);
                }
            }
            System.out.println("✓ 已连接 " + tools.size() + " 个预定义 MCP 工具");
            return !tools.isEmpty();
        } catch (Exception e) {
            System.err.println("✗ 连接预定义 MCP 工具失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🔥 断开 MCP 服务器
     */
    public void disconnectMCPServer(String serverName) {
        if (mcpService != null) {
            mcpService.disconnectServer(serverName);
            System.out.println("✓ 已断开 MCP 服务器: " + serverName);
        }
    }

    /**
     * 🔥 获取 MCP 工具信息
     */
    public void printMCPInfo() {
        if (mcpService == null) {
            System.out.println("MCP 服务未初始化");
            return;
        }

        var servers = mcpService.getConnectedServers();
        var tools = mcpService.getMCPTools();

        System.out.println("MCP 服务器 (" + servers.size() + " 个):");
        servers.forEach(server -> System.out.println("  - " + server));

        System.out.println("MCP 工具 (" + tools.size() + " 个):");
        tools.forEach((name, tool) ->
                System.out.println("  - " + name + ": " + tool.getDescription())
        );
    }

    /**
     * 🔥 关闭 MCP 服务
     */
    public void shutdownMCP() {
        if (mcpService != null) {
            mcpService.shutdown();
        }
        if (mcpToolManager != null) {
            mcpToolManager.shutdown();
        }
        System.out.println("MCP 服务已关闭");
    }

    // Getter方法
    public AppConfig getAppConfig() { return appConfig; }
    public MCPConfig getMcpConfig() { return mcpConfig; }
    public AIService getAiService() { return aiService; }
    public SessionService getSessionService() { return sessionService; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    // 🔥 新增 contextManager Getter
    public ContextManager getContextManager() { return contextManager; }

    // 🔥 新增 memoryService Getter（可为 null = 记忆功能关闭）
    public MemoryService getMemoryService() { return memoryService; }

    // 🔥 新增 cronScheduler Getter（可为 null = 定时任务系统关闭）
    public CronScheduler getCronScheduler() { return cronScheduler; }

    // 🔥 新增 teamManager Getter（可为 null = 团队系统关闭）
    public TeamManager getTeamManager() { return teamManager; }
    public ThoughtCodingUI getUi() { return ui; }
    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }

    // 🔥 新增 MCP 相关 Getter
    public MCPService getMcpService() { return mcpService; }
    public MCPToolManager getMcpToolManager() { return mcpToolManager; }
    public boolean isMCPEnabled() {
        return mcpConfig != null && mcpConfig.isEnabled();
    }
    public int getMCPToolCount() {
        return mcpService != null ? mcpService.getMCPTools().size() : 0;
    }

    // Builder模式
    public static class Builder {
        private AppConfig appConfig;
        private MCPConfig mcpConfig;
        private AIService aiService;
        private SessionService sessionService;
        private ToolRegistry toolRegistry;
        private ThoughtCodingUI ui;
        private PerformanceMonitor performanceMonitor;
        // 🔥 新增 MCP 字段
        private MCPService mcpService;
        private MCPToolManager mcpToolManager;
        // 🔥 新增上下文管理器字段
        private ContextManager contextManager;
        // 🔥 新增记忆系统字段
        private MemoryService memoryService;
        // 🔥 新增定时任务(cron)调度器字段
        private CronScheduler cronScheduler;
        // 🔥 新增团队(Agent Teams)管理器字段
        private TeamManager teamManager;

        public Builder appConfig(AppConfig appConfig) {
            this.appConfig = appConfig;
            return this;
        }

        public Builder mcpConfig(MCPConfig mcpConfig) {
            this.mcpConfig = mcpConfig;
            return this;
        }

        public Builder aiService(AIService aiService) {
            this.aiService = aiService;
            return this;
        }

        public Builder sessionService(SessionService sessionService) {
            this.sessionService = sessionService;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        public Builder ui(ThoughtCodingUI ui) {
            this.ui = ui;
            return this;
        }

        public Builder performanceMonitor(PerformanceMonitor performanceMonitor) {
            this.performanceMonitor = performanceMonitor;
            return this;
        }

        // 🔥 新增 MCP Builder 方法
        public Builder mcpService(MCPService mcpService) {
            this.mcpService = mcpService;
            return this;
        }

        public Builder mcpToolManager(MCPToolManager mcpToolManager) {
            this.mcpToolManager = mcpToolManager;
            return this;
        }

        // 🔥 新增 contextManager Builder 方法
        public Builder contextManager(ContextManager contextManager) {
            this.contextManager = contextManager;
            return this;
        }

        // 🔥 新增 memoryService Builder 方法
        public Builder memoryService(MemoryService memoryService) {
            this.memoryService = memoryService;
            return this;
        }

        // 🔥 新增 cronScheduler Builder 方法
        public Builder cronScheduler(CronScheduler cronScheduler) {
            this.cronScheduler = cronScheduler;
            return this;
        }

        // 🔥 新增 teamManager Builder 方法
        public Builder teamManager(TeamManager teamManager) {
            this.teamManager = teamManager;
            return this;
        }

        public ThoughtCodingContext build() {
            return new ThoughtCodingContext(this);
        }
    }
}