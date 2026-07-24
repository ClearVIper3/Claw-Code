package com.thoughtcoding.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把 {@link BaseTool} 转换为 langchain4j 原生 {@link ToolSpecification}。
 *
 * <ul>
 *   <li>四个内置工具用固定的 JSON 参数 schema；</li>
 *   <li>MCP 等带 {@code getInputSchema()} 的工具，尽力把其 JSON-schema 结构转成 {@link JsonObjectSchema}；</li>
 *   <li>任何无法识别/转换失败的工具，兜底为通用的 {@code {input: string}}，绝不因单个坏 schema 阻塞整次请求。</li>
 * </ul>
 */
public final class ToolSpecificationFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSpecificationFactory() {
    }

    public static ToolSpecification build(BaseTool tool) {
        String name = tool.getName();
        String description = tool.getDescription();

        JsonObjectSchema params = builtInSchema(name);
        if (params == null) {
            params = fromRawSchema(tool.getInputSchema());
        }
        if (params == null) {
            params = genericSchema();
        }

        return ToolSpecification.builder()
                .name(name)
                .description(description == null || description.isBlank() ? name : description)
                .parameters(params)
                .build();
    }

    private static JsonObjectSchema builtInSchema(String name) {
        switch (name) {
            case "bash":
                return JsonObjectSchema.builder()
                        .addStringProperty("command", "要执行的 shell 命令")
                        .addIntegerProperty("timeout", "超时秒数（可选）")
                        .required("command")
                        .additionalProperties(false)
                        .build();
            case "read":
                return JsonObjectSchema.builder()
                        .addStringProperty("path", "要读取的文件路径")
                        .addIntegerProperty("offset", "起始行号（1 起，可选）")
                        .addIntegerProperty("limit", "读取行数（可选）")
                        .required("path")
                        .additionalProperties(false)
                        .build();
            case "write":
                return JsonObjectSchema.builder()
                        .addStringProperty("path", "要写入的文件路径")
                        .addStringProperty("content", "文件内容（覆盖写）")
                        .required("path", "content")
                        .additionalProperties(false)
                        .build();
            case "edit":
                return JsonObjectSchema.builder()
                        .addStringProperty("path", "要修改的文件路径")
                        .addStringProperty("old_string", "被替换的原文本")
                        .addStringProperty("new_string", "替换后的新文本")
                        .addBooleanProperty("replace_all", "是否替换全部匹配（默认 false）")
                        .required("path", "old_string", "new_string")
                        .additionalProperties(false)
                        .build();
            case "glob":
                return JsonObjectSchema.builder()
                        .addStringProperty("pattern", "文件名匹配模式，如 **/*.java")
                        .addStringProperty("path", "搜索起始目录（可选，默认当前目录）")
                        .required("pattern")
                        .additionalProperties(false)
                        .build();
            default:
                return null;
        }
    }

    private static JsonObjectSchema genericSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("input", "工具输入（原始字符串或 JSON）")
                .build();
    }

    /** 尽力把 MCP 等工具的原始 JSON-schema（type/properties/required）转换为 JsonObjectSchema。 */
    @SuppressWarnings("unchecked")
    private static JsonObjectSchema fromRawSchema(Object rawSchema) {
        if (rawSchema == null) {
            return null;
        }
        try {
            Map<String, Object> schema = MAPPER.convertValue(rawSchema, Map.class);
            Object propsObj = schema.get("properties");
            if (!(propsObj instanceof Map)) {
                return null;
            }
            Map<String, Object> props = (Map<String, Object>) propsObj;
            JsonObjectSchema.Builder b = JsonObjectSchema.builder();

            for (Map.Entry<String, Object> e : props.entrySet()) {
                String pname = e.getKey();
                Map<String, Object> pdef = (e.getValue() instanceof Map)
                        ? (Map<String, Object>) e.getValue() : Map.of();
                String pdesc = str(pdef.get("description"));
                Object enumVals = pdef.get("enum");
                String type = str(pdef.get("type"));

                if (enumVals instanceof List) {
                    List<String> vals = ((List<?>) enumVals).stream()
                            .map(String::valueOf).collect(Collectors.toList());
                    b.addEnumProperty(pname, vals, pdesc);
                } else if ("integer".equals(type)) {
                    b.addIntegerProperty(pname, pdesc);
                } else if ("number".equals(type)) {
                    b.addNumberProperty(pname, pdesc);
                } else if ("boolean".equals(type)) {
                    b.addBooleanProperty(pname, pdesc);
                } else {
                    b.addStringProperty(pname, pdesc);
                }
            }

            Object req = schema.get("required");
            if (req instanceof List) {
                List<String> reqs = ((List<?>) req).stream()
                        .map(String::valueOf).collect(Collectors.toList());
                if (!reqs.isEmpty()) {
                    b.required(reqs);
                }
            }
            return b.build();
        } catch (Exception ex) {
            return null; // 兜底走 generic
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
