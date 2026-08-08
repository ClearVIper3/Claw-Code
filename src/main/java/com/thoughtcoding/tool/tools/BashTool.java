package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 工具：执行任意 shell 命令，返回合并后的 stdout/stderr。
 * 无命令白名单——安全由 AgentLoop 的“执行前确认”把关。
 */
public class BashTool extends BaseTool {
    private final int defaultTimeoutSeconds;
    private static final String SHELL =
            System.getProperty("os.name").toLowerCase().contains("win") ? "PowerShell" : "bash";

    public BashTool(AppConfig appConfig) {
        super("bash", "执行任意 " + SHELL + " 命令，返回合并的 stdout/stderr。需要搜索文件内容时也用它（如 grep/rg）。参数：command（必填）、timeout（可选，秒）、run_in_background（可选，布尔）。⚠️ 后台任务（run_in_background=true）的命令往往耗时很长，务必显式传足够大的 timeout（建议 600 秒以上），否则会因默认超时被提前终止。");
        Integer t = appConfig.getTools().getBash().getTimeoutSeconds();
        this.defaultTimeoutSeconds = (t == null || t <= 0) ? 60 : t;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("command", "要执行的 shell 命令")
                .addIntegerProperty("timeout", "超时秒数（可选）")
                .addBooleanProperty("run_in_background",
                        "设为 true 时在后台异步执行，立即返回占位结果；"
                                + "命令完成后通过 <task_notification> 通知回报结果。"
                                + "用于构建/测试/长轮询等耗时命令。")
                .required("command")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> params = mapper.readValue(input, Map.class);

            Object cmdObj = params.get("command");
            if (cmdObj == null || cmdObj.toString().trim().isEmpty()) {
                return error("bash 需要 'command' 字段", System.currentTimeMillis() - startTime);
            }
            String command = cmdObj.toString();

            int timeoutSeconds = defaultTimeoutSeconds;
            Object t = params.get("timeout");
            if (t instanceof Number && ((Number) t).intValue() > 0) {
                timeoutSeconds = ((Number) t).intValue();
            }

            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                String script = "$ProgressPreference = 'SilentlyContinue'; "
                        + "$ErrorActionPreference = 'Continue'; "
                        + "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                        + "& { " + command + " } 2>&1 | Out-String -Width 300";
                // 转为 UTF-16LE 字节序列
                byte[] bytes = script.getBytes(StandardCharsets.UTF_16LE);
                String encoded = Base64.getEncoder().encodeToString(bytes);
                pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand", encoded);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 后台线程消费 stdout，避免主线程因 readLine 阻塞而无法触发超时
            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception ignored) {
                    // 进程被 destroy 后流关闭，忽略
                }
            }, "bash-stdout-reader");
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                return error("命令超时（>" + timeoutSeconds + "s），已被终止",
                        System.currentTimeMillis() - startTime);
            }

            // 进程已退出，等 reader 线程收完最后几行输出
            readerThread.join(5000);

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (exitCode != 0) {
                return error("命令退出码 " + exitCode + ":\n" + result, System.currentTimeMillis() - startTime);
            }
            return success(result.isEmpty() ? "命令执行成功（无输出）" : result,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return error("命令执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
