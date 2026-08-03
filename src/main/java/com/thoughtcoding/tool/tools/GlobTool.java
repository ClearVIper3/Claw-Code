package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.exception.WorkspaceSecurityException;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.tool.Sandbox;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * glob 工具：按文件名模式（如 **&#47;*.java）查找文件，命中按最后修改时间倒序。
 */
public class GlobTool extends BaseTool {
    private static final int MAX_RESULTS = 250;
    private static final int MAX_DEPTH = 20;

    /** 为性能跳过的大型/虚拟目录 */
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git", ".svn", ".hg");

    public GlobTool(AppConfig appConfig) {
        super("glob", "按文件名模式查找文件（如 **/*.java），结果按最后修改时间倒序。参数：pattern（必填）、path（可选，起始目录，默认当前目录）。");
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("pattern", "文件名匹配模式，如 **/*.java")
                .addStringProperty("path", "搜索起始目录（可选，默认当前目录）")
                .required("pattern")
                .additionalProperties(false)
                .build();
    }

    @Override
    public boolean isReadOnly() {
        return true;
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
            Path base = Sandbox.resolve(basePathStr);

            if (!Files.exists(base) || !Files.isDirectory(base)) {
                return error("目录不存在: " + basePathStr, System.currentTimeMillis() - startTime);
            }

            final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            List<Path> matches = new ArrayList<>();
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir,
                                BasicFileAttributes attrs) {
                            Path name = dir.getFileName();
                            if (name != null && SKIP_DIRS.contains(name.toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file,
                                BasicFileAttributes attrs) {
                            Path rel = base.relativize(file);
                            // 对根目录的直接文件（无父目录），补一个 "./" 前缀，
                            // 使得 **/xxx 之类的递归模式也能匹配根级文件
                            if (rel.getParent() == null) {
                                rel = Path.of(".", rel.toString());
                            }
                            if (matcher.matches(rel) || matcher.matches(file.getFileName())) {
                                matches.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file,
                                IOException exc) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    });

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

        } catch (WorkspaceSecurityException e) {
            return error(e.getMessage(), System.currentTimeMillis() - startTime);
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
