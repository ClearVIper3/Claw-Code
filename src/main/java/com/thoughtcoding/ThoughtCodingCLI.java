package com.thoughtcoding;

import com.thoughtcoding.cli.ThoughtCodingCommand;
import com.thoughtcoding.cli.SessionCommand;
import com.thoughtcoding.cli.ConfigCommand;
import com.thoughtcoding.core.ThoughtCodingContext;
import picocli.CommandLine;

/**
 * 创建整个应用的根上下文，作为所有组件的容器
 *
 * 调用 initialize() 方法加载配置、注册工具、连接MCP服务器
 *
 * 建立命令解析框架，为后续的命令路由做准备
 */
public class ThoughtCodingCLI {
    public static void main(String[] args) {
        // 设置默认异常处理
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("💥 发生未预期错误: " + throwable.getMessage());
            System.exit(1);
        });

        int exitCode = 1;
        // Context 是应用级资源边界；无论正常返回、命令异常还是参数解析失败都会关闭。
        try (ThoughtCodingContext context = ThoughtCodingContext.initialize()) {
            CommandLine commandLine = new CommandLine(new ThoughtCodingCommand(context));
            commandLine.addSubcommand("session", new SessionCommand(context));
            commandLine.addSubcommand("config", new ConfigCommand(context));
            exitCode = commandLine.execute(args);
        } catch (Exception e) {
            System.err.println("💥 应用运行失败: " + e.getMessage());
        }
        System.exit(exitCode);
    }
}
