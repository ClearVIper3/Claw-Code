package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.tool.SkillRegistry;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * skill 工具：按需加载某个技能(Skill)的完整说明/流程/参考资料。
 *
 * <p>技能目录（名称+简介）已常驻注入 system prompt（见 {@code ContextManager}），零额外
 * API 调用；当前分类工具不足以完成任务、需要某个技能定义的专用流程或参考资料时，
 * 调用本工具传入技能名称获取该技能 SKILL.md 的完整正文。加载后的内容不是常驻 system
 * prompt，而是作为一次 tool_result 进入当前对话历史，随后续对话一起保留（直到被压缩/截断）。
 *
 * <p>技能正文里若引用了 references/scripts/assets 等资源，可直接用已有的 read/bash/glob
 * 工具以相对路径（{@code skills/<name>/...}）访问，本工具不重复该能力。
 */
public class SkillTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillRegistry registry;

    public SkillTool(SkillRegistry registry) {
        super("skill",
                "按需加载某个技能(Skill)的完整说明。技能目录（名称+简介）已在系统提示中列出；"
                + "当需要某个技能定义的专用流程/规范/参考资料时，调用本工具传入技能名称获取完整内容。\n"
                + "注意：加载后的内容会保留在后续对话历史中（并非常驻系统提示）；"
                + "技能正文中若引用了其它文件（references/scripts/assets），"
                + "直接用 read/bash/glob 工具以相对路径访问即可，无需再调用本工具。");
        this.registry = registry;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addEnumProperty("name", registry.names(), "要加载的技能名称（须为系统提示技能目录中列出的名称之一）")
                .required("name")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);

            Object nameObj = params.get("name");
            String name = nameObj == null ? "" : nameObj.toString().trim();
            if (name.isEmpty()) {
                return error("skill 需要 'name' 字段（技能名称）", System.currentTimeMillis() - startTime);
            }

            String content = registry.load(name);
            if (content == null) {
                return error("未找到技能: " + name + "。可用技能: " + String.join(", ", registry.names()),
                        System.currentTimeMillis() - startTime);
            }

            return success(content, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("skill 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
