package com.thoughtcoding.tools.search;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.model.ToolResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grep 搜索工具，支持在文件和目录中搜索文本模式
 */
public class GrepSearchTool extends BaseTool {

    private final AppConfig appConfig;
    public GrepSearchTool(AppConfig appConfig) {
        super("grep_search", "Search for text patterns in files using grep-like functionality");
        this.appConfig = appConfig;
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();

        try {
            // 解析 pattern / path：优先 JSON（原生工具参数），否则回退到空格分隔
            String rawPattern;
            String rawPath;
            if (input != null && input.trim().startsWith("{")) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, Object> params = mapper.readValue(input, java.util.Map.class);
                Object pat = params.get("pattern");
                Object pth = params.get("path");
                if (pat == null || pth == null) {
                    return error("JSON 格式错误：需要 'pattern' 和 'path' 字段", System.currentTimeMillis() - startTime);
                }
                rawPattern = pat.toString();
                rawPath = pth.toString();
            } else {
                String[] parts = input.split(" ", 2);
                if (parts.length < 2) {
                    return error("Invalid format. Use: <pattern> <path>", System.currentTimeMillis() - startTime);
                }
                rawPattern = parts[0];
                rawPath = parts[1];
            }

            final String pattern = rawPattern;
            final String searchPath = rawPath;

            Path path = Paths.get(searchPath).toAbsolutePath();

            if (!Files.exists(path)) {
                return error("Path not found: " + path, System.currentTimeMillis() - startTime);
            }

            List<String> results = new ArrayList<>();

            if (Files.isDirectory(path)) {
                // 递归搜索目录
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (Files.isRegularFile(file) && !Files.isHidden(file)) {
                            searchInFile(file, pattern, results);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                // 搜索单个文件
                searchInFile(path, pattern, results);
            }

            if (results.isEmpty()) {
                return success("No matches found for pattern: " + pattern, System.currentTimeMillis() - startTime);
            }

            String result = String.join("\n", results);
            return success("Found " + results.size() + " matches:\n" + result, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return error("Search failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private void searchInFile(Path file, String pattern, List<String> results) {
        try {
            Pattern regex;
            try {
                regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                // 如果正则表达式无效，使用普通文本搜索
                regex = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
            }

            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    results.add(String.format("%s:%d: %s", file, i + 1, lines.get(i).trim()));
                }
            }
        } catch (IOException e) {
            results.add(String.format("%s: ERROR - %s", file, e.getMessage()));
        }
    }

    @Override
    public String getCategory() {
        return "exec";
    }

    @Override
    public boolean isEnabled() {
        return appConfig != null && appConfig.getTools().getCommandExec().isEnabled();
    }
}