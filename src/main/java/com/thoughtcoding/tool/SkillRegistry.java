package com.thoughtcoding.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能(Skill)注册表 —— 启动时扫描一次 {@code <workdir>/skills/*&#47;SKILL.md}，
 * 解析 YAML frontmatter（name/description）建立轻量目录，正文按需通过 {@link #load(String)} 取出。
 *
 * <p>两级设计：目录（name+description）常驻注入 system prompt，零额外 API 调用；
 * 完整正文只在模型显式调用 {@code skill} 工具后才作为一次 tool_result 进入对话历史。
 * 查找走本注册表的 Map，不拼路径，没有路径遍历风险。
 */
public final class SkillRegistry {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** frontmatter 块：开头 ---，正文前再一个 ---（\R 兼容 \n 与 \r\n）。 */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*\\R?(.*)\\z", Pattern.DOTALL);

    /** 单个技能：name/description 用于目录展示，content 为去除 frontmatter 后的正文。 */
    public record Skill(String name, String description, String content) {
    }

    private final Map<String, Skill> skills;

    private SkillRegistry(Map<String, Skill> skills) {
        this.skills = skills;
    }

    /**
     * 扫描 skillsDir 下的所有子目录，每个子目录若含 SKILL.md 则解析为一个技能。
     * 目录不存在、不是目录、或单个 SKILL.md 解析失败，均不阻塞——分别降级为空注册表 / 跳过该项。
     */
    public static SkillRegistry scan(Path skillsDir) {
        Map<String, Skill> map = new LinkedHashMap<>();
        if (skillsDir != null && Files.isDirectory(skillsDir)) {
            try (var stream = Files.list(skillsDir)) {
                List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
                for (Path dir : dirs) {
                    Path manifest = dir.resolve("SKILL.md");
                    if (!Files.isRegularFile(manifest)) {
                        continue;
                    }
                    try {
                        String raw = Files.readString(manifest);
                        Skill skill = parse(dir.getFileName().toString(), raw);
                        map.put(skill.name(), skill);
                    } catch (Exception e) {
                        // 单个技能解析失败不阻塞其余技能扫描
                    }
                }
            } catch (IOException ignored) {
                // 目录列举失败 → 降级为空注册表
            }
        }
        return new SkillRegistry(map);
    }

    @SuppressWarnings("unchecked")
    private static Skill parse(String dirName, String raw) {
        String name = dirName;
        String description = null;
        String body = raw;

        Matcher m = FRONTMATTER.matcher(raw);
        if (m.matches()) {
            String fm = m.group(1);
            body = m.group(2);
            try {
                Map<String, Object> meta = YAML_MAPPER.readValue(fm, Map.class);
                Object n = meta.get("name");
                if (n != null && !n.toString().isBlank()) {
                    name = n.toString().trim();
                }
                Object d = meta.get("description");
                if (d != null && !d.toString().isBlank()) {
                    description = d.toString().trim();
                }
            } catch (Exception ignored) {
                // frontmatter 非法 YAML → 忽略元数据，整份原文当正文
                body = raw;
            }
        }

        if (description == null || description.isBlank()) {
            description = firstNonBlankLine(body);
        }

        return new Skill(name, description, body.strip());
    }

    private static String firstNonBlankLine(String body) {
        for (String line : body.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return t.replaceFirst("^#+\\s*", "");
            }
        }
        return "";
    }

    /** 目录展示：`- **name**: description` 逐行拼接，供 system prompt 注入。 */
    public String catalog() {
        StringBuilder sb = new StringBuilder();
        for (Skill s : skills.values()) {
            sb.append("- **").append(s.name()).append("**: ").append(s.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 按名称取完整正文（不含 frontmatter），未命中返回 null。 */
    public String load(String name) {
        Skill s = skills.get(name);
        return s == null ? null : s.content();
    }

    /** 已知技能名（保序），用于构建工具入参的枚举 schema。 */
    public List<String> names() {
        return List.copyOf(skills.keySet());
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }
}
