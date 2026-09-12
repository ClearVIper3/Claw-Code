package com.thoughtcoding.ui.component;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.ui.AnsiColors;
import org.jline.terminal.Terminal;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 聊天渲染组件，负责在终端中美观地展示用户和AI的消息，以及会话列表
 */
public class ChatRenderer {
    private final Terminal terminal;
    private final DateTimeFormatter timeFormatter;

    public ChatRenderer(Terminal terminal) {
        this.terminal = terminal;
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    }

    public void renderUserMessage(ChatMessage message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String formattedMessage = String.format("%s[%s] %sYou:%s %s",
                AnsiColors.BRIGHT_BLACK, timestamp, AnsiColors.BRIGHT_BLUE, AnsiColors.RESET, message.getContent());

        terminal.writer().println(formattedMessage);
        terminal.writer().flush();
    }

    public void renderSessionList(List<String> sessions) {
        if (sessions.isEmpty()) {
            terminal.writer().println(AnsiColors.YELLOW + "No sessions found." + AnsiColors.RESET);
            return;
        }

        terminal.writer().println(AnsiColors.BRIGHT_CYAN + "📚 Session List:" + AnsiColors.RESET);
        terminal.writer().println(AnsiColors.BRIGHT_BLACK + "========================" + AnsiColors.RESET);

        for (int i = 0; i < sessions.size(); i++) {
            String sessionId = sessions.get(i);
            String shortId = sessionId.length() > 8 ? sessionId.substring(0, 8) + "..." : sessionId;

            String line = String.format("%s%d. %s%s %s(%s)%s",
                    AnsiColors.BRIGHT_WHITE, i + 1,
                    AnsiColors.BRIGHT_GREEN, "🟢",
                    AnsiColors.BRIGHT_BLACK, shortId, AnsiColors.RESET);

            terminal.writer().println(line);
        }

        terminal.writer().flush();
    }
}
