package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.tool.Sandbox;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * edit 工具：对文件做精确字符串替换。
 * old_string 未找到、或出现多次且未设 replace_all 时报错。
 */
public class EditTool extends BaseTool {

    public EditTool(AppConfig appConfig) {
        super("edit", "对文件做精确字符串替换。old_string 必须是文件中的原文（不是 Read 工具输出的带行号的格式），将 old_string 替换为 new_string。old_string 必须在文件中唯一，否则需设 replace_all=true。参数：path、old_string、new_string（必填）、replace_all（可选）。");
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("path", "要修改的文件路径")
                .addStringProperty("old_string", "被替换的原文本")
                .addStringProperty("new_string", "替换后的新文本")
                .addBooleanProperty("replace_all", "是否替换全部匹配（默认 false）")
                .required("path", "old_string", "new_string")
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
            Object oldObj = params.get("old_string");
            Object newObj = params.get("new_string");
            if (p == null || oldObj == null || newObj == null) {
                return error("edit 需要 'path'、'old_string'、'new_string' 字段",
                        System.currentTimeMillis() - startTime);
            }
            String oldString = oldObj.toString();
            String newString = newObj.toString();
            if (oldString.isEmpty()) {
                return error("old_string 不能为空", System.currentTimeMillis() - startTime);
            }
            boolean replaceAll = Boolean.TRUE.equals(params.get("replace_all"))
                    || "true".equalsIgnoreCase(String.valueOf(params.get("replace_all")));

            Path path = Sandbox.resolve(p.toString());
            if (!Files.exists(path) || Files.isDirectory(path)) {
                return error("文件不存在: " + p, System.currentTimeMillis() - startTime);
            }

            String content = Files.readString(path).replace("\r\n", "\n");
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return error("未找到 old_string，未做修改", System.currentTimeMillis() - startTime);
            }
            if (count > 1 && !replaceAll) {
                return error("old_string 出现 " + count + " 次，不唯一；请补充上下文使其唯一，或设置 replace_all=true",
                        System.currentTimeMillis() - startTime);
            }

            String result;
            if (replaceAll) {
                result = content.replace(oldString, newString);
            } else {
                int idx = content.indexOf(oldString);
                result = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
            }
            Files.writeString(path, result);

            return success("已在 " + path + " 替换 " + (replaceAll ? count : 1) + " 处",
                    System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("编辑失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("edit 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
