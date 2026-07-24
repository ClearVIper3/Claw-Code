package com.thoughtcoding.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.mcp.model.MCPTool;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool; // 使用你的 BaseTool 基类
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP服务，管理与多个MCP服务器的连接和工具调用
 */
public class MCPService {
    private static final Logger log = LoggerFactory.getLogger(MCPService.class);
    private final Map<String, MCPClient> connectedServers = new ConcurrentHashMap<>();
    private final Map<String, BaseTool> mcpTools = new ConcurrentHashMap<>(); // 改为 BaseTool
    private final Map<String, MCPClient> clients = new ConcurrentHashMap<>();


    // 🔥 新增3参数方法
    public List<BaseTool> connectToServer(String serverName, String command, List<String> args) {
        try {
            log.debug("启动MCP服务器: {} - {}", serverName, command);
            log.debug("参数: {}", args);

            // 清理旧连接
            if (clients.containsKey(serverName)) {
                MCPClient existingClient = clients.get(serverName);
                if (existingClient != null && existingClient.isConnected()) {
                    existingClient.disconnect();
                }
                clients.remove(serverName);
                connectedServers.remove(serverName); // 🔥 同时清理 connectedServers
            }

            MCPClient client = new MCPClient(serverName);
            boolean connected = client.connect(command, args);

            if (connected) {
                // 🔥 保存到两个映射中
                clients.put(serverName, client);
                connectedServers.put(serverName, client);

                List<MCPTool> mcpToolList = client.getAvailableTools();
                List<BaseTool> baseTools = convertToBaseTools(mcpToolList, serverName);

                // 🔥 保存工具到 mcpTools 映射
                for (int i = 0; i < mcpToolList.size(); i++) {
                    String toolKey = mcpToolList.get(i).getName(); // 使用工具名称作为key
                    mcpTools.put(toolKey, baseTools.get(i));
                }

                log.debug("✅ 成功连接MCP服务器: {} ({} 个工具)", serverName, baseTools.size());
                return baseTools;
            } else {
                log.debug("⚠️ 连接MCP服务器失败: {}", serverName);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("❌ 连接MCP服务器异常: {}", serverName, e);
            return Collections.emptyList();
        }
    }

    private List<BaseTool> convertToBaseTools(List<MCPTool> mcpTools, String serverName) {
        List<BaseTool> baseTools = new ArrayList<>();
        for (MCPTool mcpTool : mcpTools) {
            BaseTool baseTool = new BaseTool(mcpTool.getName(), mcpTool.getDescription()) {
                @Override
                public ToolResult execute(String input) {
                    try {
                        // 🔥 修复：正确解析JSON参数
                        Map<String, Object> parameters = parseInputToParameters(input);
                        Object result = callTool(serverName, mcpTool.getName(), parameters);
                        return success(result != null ? result.toString() : "执行成功");
                    } catch (Exception e) {
                        return error("工具执行失败: " + e.getMessage());
                    }
                }

                // 🔥 关键修复：暴露inputSchema给系统提示词（重写BaseTool方法）
                public Object getInputSchema() {
                    return mcpTool.getInputSchema();
                }
            };
            baseTools.add(baseTool);
        }
        return baseTools;
    }

    /**
     * 🔥 修复：优先解析JSON格式的参数
     * 如果输入是JSON对象，直接解析为Map；否则作为单个参数
     */
    private Map<String, Object> parseInputToParameters(String input) {
        Map<String, Object> parameters = new HashMap<>();

        if (input == null || input.trim().isEmpty()) {
            return parameters;
        }

        // 🔥 优先尝试解析JSON
        if (input.trim().startsWith("{")) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> parsed = mapper.readValue(input, Map.class);
                log.debug("✅ 成功解析JSON参数: {}", parsed);
                return parsed;
            } catch (Exception e) {
                log.debug("⚠️ JSON解析失败，使用默认解析: {}", e.getMessage());
            }
        }

        // 如果不是JSON或解析失败，将整个输入作为单个参数
        parameters.put("input", input);
        return parameters;
    }

    public Object callTool(String serverName, String toolName, Map<String, Object> arguments) {
        try {
            MCPClient client = clients.get(serverName);
            if (client == null) {
                throw new IllegalStateException("MCP服务器未连接: " + serverName);
            }
            return client.callTool(toolName, arguments);
        } catch (Exception e) {
            log.error("调用工具失败: {}.{}", serverName, toolName, e);
            throw new RuntimeException("工具调用失败: " + e.getMessage(), e);
        }
    }

    public void disconnectServer(String serverName) {
        MCPClient client = connectedServers.remove(serverName);
        if (client != null) {
            client.disconnect();
            log.debug("已断开MCP服务器: {}", serverName);
        }
    }

    public List<String> getConnectedServers() {
        return new ArrayList<>(connectedServers.keySet());
    }

    public Map<String, BaseTool> getMCPTools() {
        return new HashMap<>(mcpTools);
    }

    public void shutdown() {
        log.info("关闭所有MCP连接...");
        new ArrayList<>(connectedServers.keySet()).forEach(this::disconnectServer);
    }
}