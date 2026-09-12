package com.thoughtcoding.ui;

import com.thoughtcoding.model.ChatMessage;

import com.thoughtcoding.ui.component.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.StringsCompleter;

import java.io.IOException;
import java.util.List;

/**
 * ThoughtCodingUI 类，管理终端用户界面和交互
 */
public class ThoughtCodingUI implements AutoCloseable {
    private final Terminal terminal;
    private final LineReader lineReader;
    private final ChatRenderer chatRenderer;
    private final StatusBar statusBar;
    private final InputHandler inputHandler;

    /** 流式输出行缓冲：token 片段攒成整行后经 printAbove 上屏。 */
    private final StringBuilder assistantStreamBuffer = new StringBuilder();
    private final Object assistantStreamLock = new Object();

    public Terminal getTerminal() {
        return terminal;
    }

    public LineReader getLineReader() {
        return lineReader;
    }

    public ThoughtCodingUI() {
        try {
            // 🔥 禁用 JLine 的日志输出，避免警告信息
            System.setProperty("org.jline.terminal.dumb", "true");

            // 初始化JLine终端
            this.terminal = TerminalBuilder.builder()
                    .name("ThoughtCoding") // 终端名称
                    .system(true) // 使用系统终端
                    .build(); // 构建终端

            // 初始化行阅读器
            this.lineReader = LineReaderBuilder.builder()
                    .terminal(terminal) //关联终端
                    .completer(new StringsCompleter("exit", "quit", "clear", "help", "stop",
                            "/commands", "/mcp", "/agents", "/agents list", "/agents cleanup"))//命令补全
                    .build();//构建行阅读器

            // 初始化UI组件
            this.chatRenderer = new ChatRenderer(terminal);//聊天渲染器
            this.statusBar = new StatusBar(terminal, lineReader);//状态栏
            this.inputHandler = new InputHandler(lineReader);//输入处理器（复用全局 reader，见 InputHandler 类注释）

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize terminal", e);
        }
    }

    /** 启动横幅文本。行尾的 \s 转义是文本块中保留对齐空格的写法，勿删。 */
    private static final String BANNER_ART = """
                ░██████████░██                                         ░██           ░██      ░██████                    ░██ ░██                     \s
                    ░██    ░██                                         ░██           ░██     ░██   ░██                   ░██                         \s
                    ░██    ░████████   ░███████  ░██    ░██  ░████████ ░████████  ░████████ ░██         ░███████   ░████████ ░██░████████   ░████████\s
                    ░██    ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██    ░██    ░██        ░██    ░██ ░██    ░██ ░██░██    ░██ ░██    ░██\s
                    ░██    ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██    ░██    ░██        ░██    ░██ ░██    ░██ ░██░██    ░██ ░██    ░██\s
                    ░██    ░██    ░██ ░██    ░██ ░██   ░███ ░██   ░███ ░██    ░██    ░██     ░██   ░██ ░██    ░██ ░██   ░███ ░██░██    ░██ ░██   ░███\s
                    ░██    ░██    ░██  ░███████   ░█████░██  ░█████░██ ░██    ░██     ░████   ░██████   ░███████   ░█████░██ ░██░██    ░██  ░█████░██\s
                                                               ░██                                                                            ░██\s
                                                         ░███████                                                                       ░███████ \s
                                                                                                                                                 \s        """;

    private static final String[] BANNER_TITLES = {
            "Interactive Code Assistant CLI",
            "- Java Edition -",
            "Version 2.0.0"
    };

    private static final int BANNER_WIDTH = 122;

    public void showBanner() {
        terminal.writer().println(AnsiColors.GREEN + BANNER_ART + AnsiColors.RESET);

        for (String line : BANNER_TITLES) {
            int padding = (BANNER_WIDTH - line.length()) / 2;
            terminal.writer().println(AnsiColors.CYAN + " ".repeat(Math.max(0, padding)) + line + AnsiColors.RESET);
        }

        terminal.writer().println();
    }

    public void displayUserMessage(ChatMessage message) {
        chatRenderer.renderUserMessage(message);
    }

    public void displayAIMessage(ChatMessage message) {
        if (message.isAssistantMessage()) {
            appendAssistantStream(message.getContent());
        }
    }

    /**
     * 回合线程安全输出：经 LineReader.printAbove 打印。JLine 会先擦掉当前输入行、
     * 打印内容、再重绘 thought> 提示符与已输入缓冲，agent 回合的输出不会与
     * 提示符行互相覆盖；非读入状态（主线程同步路径）等价于普通 println。
     */
    public void printAbove(String text) {
        lineReader.printAbove(text == null ? "" : text);
    }

    /**
     * 流式 token 是半行片段，而 printAbove 以整行为单位（擦提示符行 → 打印 → 重绘），
     * 不能逐 token 上屏；这里把片段攒成整行，遇到换行才打印已完成的行。
     */
    private void appendAssistantStream(String fragment) {
        synchronized (assistantStreamLock) {
            if (fragment == null) {
                return;
            }
            assistantStreamBuffer.append(fragment);
            int newline;
            while ((newline = assistantStreamBuffer.indexOf("\n")) >= 0) {
                String line = assistantStreamBuffer.substring(0, newline);
                assistantStreamBuffer.delete(0, newline + 1);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                printAbove(AnsiColors.BRIGHT_CYAN + line + AnsiColors.RESET);
            }
        }
    }

    /** 流式结束（或异常中断）时把残余半行上屏；缓冲为空时是 no-op。 */
    public void flushAssistantStream() {
        synchronized (assistantStreamLock) {
            if (assistantStreamBuffer.length() > 0) {
                String line = assistantStreamBuffer.toString();
                assistantStreamBuffer.setLength(0);
                printAbove(AnsiColors.BRIGHT_CYAN + line + AnsiColors.RESET);
            }
        }
    }

    public void displayInfo(String info) {
        statusBar.showInfo(info);
    }

    public void displayError(String error) {
        statusBar.showError(error);
    }

    public void displaySuccess(String message) {
        statusBar.showSuccess(message);
    }

    public void displayWarning(String warning) {
        statusBar.showWarning(warning);
    }

    public void displaySessionList(java.util.List<String> sessions) {
        chatRenderer.renderSessionList(sessions);
    }

    public String readInput(String prompt) {
        return inputHandler.readInput(prompt);
    }

    public void clearScreen() {
        try {
            // 使用标准的 ANSI 转义序列清屏
            terminal.writer().print("\u001b[H\u001b[2J");
            terminal.writer().flush();
        } catch (Exception e) {
            // 如果清屏失败，输出换行
            for (int i = 0; i < 50; i++) {
                terminal.writer().println();
            }
            terminal.writer().flush();
        }
    }

    @Override
    public void close() {
        try {
            if (terminal != null) {
                terminal.close();
            }
        } catch (Exception e) {
            // 忽略关闭错误
        }
    }


}
