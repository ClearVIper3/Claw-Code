package com.thoughtcoding.ui.component;

import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.ui.AnsiColors;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 状态栏组件，负责在终端中显示各种状态信息，如普通信息、错误、成功、警告、性能数据等
 */
public class StatusBar {
    private final Terminal terminal;
    private final LineReader lineReader;
    private final DateTimeFormatter timeFormatter;

    public StatusBar(Terminal terminal, LineReader lineReader) {
        this.terminal = terminal;
        this.lineReader = lineReader;
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    }

    /**
     * 统一输出：经 LineReader.printAbove 打印，agent 回合线程输出时 JLine
     * 会重绘 thought> 提示符行，避免状态消息与提示符互相覆盖。
     */
    private void emit(String message) {
        lineReader.printAbove(message);
    }

    public void showInfo(String info) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String message = String.format("%s[%s] ℹ️  %s%s",
                AnsiColors.BRIGHT_BLACK, timestamp, AnsiColors.BRIGHT_CYAN, info);

        emit(message + AnsiColors.RESET);
    }

    public void showError(String error) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String message = String.format("%s[%s] ❌ %s%s",
                AnsiColors.BRIGHT_BLACK, timestamp, AnsiColors.BRIGHT_RED, error);

        emit(message + AnsiColors.RESET);
    }

    public void showSuccess(String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String formatted = String.format("%s[%s] ✅ %s%s",
                AnsiColors.BRIGHT_BLACK, timestamp, AnsiColors.BRIGHT_GREEN, message);

        emit(formatted + AnsiColors.RESET);
    }

    public void showWarning(String warning) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String message = String.format("%s[%s] ⚠️  %s%s",
                AnsiColors.BRIGHT_BLACK, timestamp, AnsiColors.BRIGHT_YELLOW, warning);

        emit(message + AnsiColors.RESET);
    }

    public void showPerformanceInfo(PerformanceMonitor.PerformanceData data) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String message = String.format("%s[%s] 📊 Performance: %dms, %d tokens, %d tools%s",
                AnsiColors.BRIGHT_BLACK, timestamp,
                data.getExecutionTimeMs(), data.getTotalTokens(), data.getTotalToolCalls(),
                AnsiColors.RESET);

        emit(message);
    }
}