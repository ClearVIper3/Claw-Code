package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * MCP 工具注解（tools/list 返回的可选 annotations），对齐 MCP 协议的 ToolAnnotations。
 * 均为“提示（hint）”而非保证：由服务器自报，不可信，仅作元数据（展示/日后并发），不用于审批决策。
 * 用 Boolean（可空）以区分“未提供”与显式 false。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MCPToolAnnotations {
    /** 工具是否只读（不修改环境）。 */
    @JsonProperty("readOnlyHint")
    private Boolean readOnlyHint;

    /** 工具是否可能做不可逆更新（仅在非只读时有意义）。 */
    @JsonProperty("destructiveHint")
    private Boolean destructiveHint;

    /** 相同参数重复调用是否无额外副作用。 */
    @JsonProperty("idempotentHint")
    private Boolean idempotentHint;

    /** 是否与外部“开放世界”实体交互。 */
    @JsonProperty("openWorldHint")
    private Boolean openWorldHint;

    // 手动添加 getter 方法（如果 Lombok 不工作）
    public Boolean getReadOnlyHint() { return readOnlyHint; }
    public Boolean getDestructiveHint() { return destructiveHint; }
    public Boolean getIdempotentHint() { return idempotentHint; }
    public Boolean getOpenWorldHint() { return openWorldHint; }
}
