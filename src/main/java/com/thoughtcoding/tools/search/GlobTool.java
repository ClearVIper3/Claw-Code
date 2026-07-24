package com.thoughtcoding.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * glob 工具：按文件名模式（如 **&#47;*.java）查找文件，命中按最后修改时间倒序。
 */
public class GlobTool extends BaseTool {
    private static final int MAX_RESULTS = 250;

    public GlobTool(AppConfig appConfig) {
        super("glob", "Find files by glob pattern (e.g. **/*.java), newest first");
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> params = mapper.readValue(input, Map.class);

            Object patObj = params.get("pattern");
            if (patObj == null || patObj.toString().trim().isEmpty()) {
                return error("glob 需要 'pattern' 字段", System.currentTimeMillis() - startTime);
            }
            String pattern = patObj.toString();

            Object pathObj = params.get("path");
            String basePathStr = (pathObj == null || pathObj.toString().trim().isEmpty())
                    ? "." : pathObj.toString();
            Path base = Paths.get(expandUserHome(basePathStr)).toAbsolutePath().normalize();

            if (!Files.exists(base) || !Files.isDirectory(base)) {
                return error("目录不存在: " + basePathStr, System.currentTimeMillis() - startTime);
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            List<Path> matches = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile)
                        .forEach(f -> {
                            Path rel = base.relativize(f);
                            if (matcher.matches(rel) || matcher.matches(f.getFileName())) {
                                matches.add(f);
                            }
                        });
            }

            if (matches.isEmpty()) {
                return success("无匹配文件: " + pattern, System.currentTimeMillis() - startTime);
            }

            matches.sort(Comparator.comparingLong(this::lastModified).reversed());

            int shown = Math.min(matches.size(), MAX_RESULTS);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                sb.append(matches.get(i)).append("\n");
            }
            if (matches.size() > MAX_RESULTS) {
                sb.append("... (共 ").append(matches.size()).append(" 个，仅显示前 ")
                        .append(MAX_RESULTS).append(" 个)\n");
            }
            return success(sb.toString().trim(), System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("查找失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("glob 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
