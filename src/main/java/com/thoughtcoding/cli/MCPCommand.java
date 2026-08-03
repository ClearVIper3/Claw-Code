package com.thoughtcoding.cli;

import com.thoughtcoding.mcp.MCPService;
import com.thoughtcoding.mcp.MCPToolManager;
import com.thoughtcoding.tool.BaseTool;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCPCommand类用于管理MCP工具和服务器的命令行操作
 */
@Command(name = "mcp", description = "MCP工具管理")
public class MCPCommand implements Runnable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MCPCommand.class);
    @Option(names = {"--tools"}, description = "直接使用预定义MCP工具（如: github-search,file-system,sql-query）")
    private String predefinedTools;

    @Option(names = {"--connect"}, description = "连接自定义MCP服务器")
    private String connectServer;

    @Option(names = {"--command"}, description = "MCP服务器命令")
    private String serverCommand;

    @Option(names = {"--list-servers"}, description = "列出已连接的MCP服务器")
    private boolean listServers;

    @Option(names = {"--list-tools"}, description = "列出可用的MCP工具")
    private boolean listTools;

    @Option(names = {"--list-predefined"}, description = "列出预定义的MCP工具")
    private boolean listPredefined;

    @Option(names = {"--disconnect"}, description = "断开MCP服务器")
    private String disconnectServer;

    private final MCPService mcpService;
    private final MCPToolManager mcpToolManager;

    public MCPCommand(MCPService mcpService, MCPToolManager mcpToolManager) {
        this.mcpService = mcpService;
        this.mcpToolManager = mcpToolManager;
    }

    @Override
    public void run() {
        try {
            if (predefinedTools != null) {
                usePredefinedTools(predefinedTools);
            } else if (connectServer != null && serverCommand != null) {
                connectCustomServer(connectServer, serverCommand);
            } else if (listServers) {
                listServers();
            } else if (listTools) {
                listTools();
            } else if (listPredefined) {
                listPredefinedTools();
            } else if (disconnectServer != null) {
                disconnectServer(disconnectServer);
            } else {
                System.out.println("使用 --help 查看可用选项");
                showHelp();
            }
        } catch (Exception e) {
            log.error("MCP命令执行失败", e);
            System.err.println("错误: " + e.getMessage());
        }
    }

    private void usePredefinedTools(String toolsList) {
        System.out.println("连接预定义MCP工具: " + toolsList);

        List<String> toolNames = Arrays.asList(toolsList.split(","));
        List<BaseTool> connectedTools = mcpToolManager.connectPredefinedTools(toolNames); // 改为 BaseTool

        System.out.println("✓ 成功连接 " + connectedTools.size() + " 个工具");
        System.out.println("现在可以在对话中使用这些工具了！");
    }

    private void connectCustomServer(String serverName, String command) {
        System.out.println("连接自定义MCP服务器: " + serverName);
        // 🔥 修改：使用空的 List 而不是 Map.of()
        List<BaseTool> tools = mcpToolManager.connectCustomServer(serverName, command, Collections.emptyList());

        if (!tools.isEmpty()) {
            System.out.println("✓ 连接成功，加载了 " + tools.size() + " 个工具");
        } else {
            System.out.println("✗ 连接失败或未发现工具");
        }
    }

    private void listServers() {
        List<String> servers = mcpService.getConnectedServers();
        if (servers.isEmpty()) {
            System.out.println("没有已连接的MCP服务器");
        } else {
            System.out.println("已连接的MCP服务器 (" + servers.size() + " 个):");
            servers.forEach(server -> System.out.println("  - " + server));
        }
    }

    private void listTools() {
        Map<String, BaseTool> tools = mcpService.getMCPTools(); // 改为 BaseTool
        if (tools.isEmpty()) {
            System.out.println("没有可用的MCP工具");
        } else {
            System.out.println("可用的MCP工具 (" + tools.size() + " 个):");
            tools.forEach((name, tool) ->
                    System.out.printf("  - %s: %s%n", name, tool.getDescription())
            );
        }
    }

    private void listPredefinedTools() {
        List<String> predefinedTools = mcpToolManager.getPredefinedToolNames();
        System.out.println("预定义的MCP工具 (" + predefinedTools.size() + " 个):");
        predefinedTools.forEach(tool -> System.out.println("  - " + tool));
        System.out.println("\n使用方式: --tools tool1,tool2,tool3");
        System.out.println("示例: --tools github-search,file-system,calculator");
    }

    private void disconnectServer(String serverName) {
        System.out.println("断开MCP服务器: " + serverName);
        mcpToolManager.disconnectServer(serverName);
        System.out.println("✓ 断开完成");
    }

    private void showHelp() {
        System.out.println("\n🔧 MCP 工具管理命令:");
        System.out.println("────────────────────");
        System.out.println("  --tools <tool1,tool2>     使用预定义MCP工具");
        System.out.println("  --connect <name>          连接自定义MCP服务器");
        System.out.println("  --command <cmd>           MCP服务器命令");
        System.out.println("  --list-servers            列出已连接的MCP服务器");
        System.out.println("  --list-tools              列出可用的MCP工具");
        System.out.println("  --list-predefined         列出预定义的MCP工具");
        System.out.println("  --disconnect <name>       断开MCP服务器");
        System.out.println();
        System.out.println("📋 使用示例:");
        System.out.println("  thought mcp --tools github-search,file-system");
        System.out.println("  thought mcp --connect my-files --command \"npx @modelcontextprotocol/server-filesystem\"");
        System.out.println("  thought mcp --list-tools");
    }
}