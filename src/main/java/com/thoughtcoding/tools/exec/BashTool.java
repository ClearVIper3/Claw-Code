package com.thoughtcoding.tools.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 工具：执行任意 shell 命令，返回合并后的 stdout/stderr。
 * 无命令白名单——安全由 AgentLoop 的“执行前确认”把关。
 */
public class BashTool extends BaseTool {
    private final int defaultTimeoutSeconds;

    public BashTool(AppConfig appConfig) {
        super("bash", "Execute an arbitrary shell command; returns combined stdout/stderr");
        Integer t = appConfig.getTools().getBash().getTimeoutSeconds();
        this.defaultTimeoutSeconds = (t == null || t <= 0) ? 60 : t;
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
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return error("命令超时（>" + timeoutSeconds + "s），已被终止:\n" + output.toString().trim(),
                        System.currentTimeMillis() - startTime);
            }

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
