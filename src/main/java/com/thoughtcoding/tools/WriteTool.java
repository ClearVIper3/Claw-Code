package com.thoughtcoding.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * write 工具：写入（覆盖）文件，自动创建父目录。
 */
public class WriteTool extends BaseTool {

    public WriteTool(AppConfig appConfig) {
        super("write", "写入/覆盖文件，自动创建父目录。参数：path（必填）、content（必填，完整文件内容）。");
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("path", "要写入的文件路径")
                .addStringProperty("content", "文件内容（覆盖写）")
                .required("path", "content")
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

            Object p = params.get("path");
            Object c = params.get("content");
            if (p == null) {
                return error("write 需要 'path' 字段", System.currentTimeMillis() - startTime);
            }
            if (c == null) {
                return error("write 需要 'content' 字段", System.currentTimeMillis() - startTime);
            }
            String content = c.toString();

            Path path = Paths.get(expandUserHome(p.toString())).toAbsolutePath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);

            return success("已写入: " + path + " (" + content.length() + " 字符)",
                    System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("写入失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("write 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
