package com.thoughtcoding.ui.component;

import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;

/**
 * 输入处理组件，负责读取用户输入和密码，并支持自动补全功能
 */
public class InputHandler {
    private final LineReader lineReader;

    /**
     * 必须复用 UI 的全局 LineReader，不能另建实例：JLine 的 printAbove 只对
     * “正处于 readLine 的同一个 reader”做擦行→打印→重绘提示符；实例不匹配时
     * 回合线程的输出会裸写终端（粘在提示符后面），且回合结束后提示符不再重绘。
     */
    public InputHandler(LineReader lineReader) {
        this.lineReader = lineReader;
    }

    public String readInput(String prompt) {
        try {
            String line = lineReader.readLine(prompt);
            return line.replace("\\","\\\\");
        } catch (UserInterruptException e) {
            throw new RuntimeException("Operation cancelled by user");
        } catch (EndOfFileException e) {
            throw new RuntimeException("End of input");
        }
    }

    public String readInput() {
        return readInput("thought> ");
    }

    public String readPassword(String prompt) {
        try {
            return lineReader.readLine(prompt, '*');
        } catch (UserInterruptException e) {
            throw new RuntimeException("Operation cancelled by user");
        } catch (EndOfFileException e) {
            throw new RuntimeException("End of input");
        }
    }
}
