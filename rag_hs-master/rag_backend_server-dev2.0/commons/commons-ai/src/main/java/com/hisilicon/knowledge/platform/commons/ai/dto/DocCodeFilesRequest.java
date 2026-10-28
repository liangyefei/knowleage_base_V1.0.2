package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * dual-mcp {@code /doc_code/files} 请求体（已建立映射关系的文件清单，《API-DOCCODE-FILES.md》）。
 * 字段名即 wire 格式（snake_case，与 {@link DocCodeRequest} 风格一致，不加 {@code @JsonProperty}）。
 *
 * <p>{@code @JsonInclude(NON_NULL)}：{@code type} 可选（缺省=模型默认 code），未传字段一律省略，
 * 避免序列化出显式 null。</p>
 *
 * @param project 项目名（= kbId；模型侧亦接受 projects- 前缀名，平台统一传 kbId）
 * @param type    列表类型：code=代码文件列表（模型默认）；doc=文档文件列表；其他值模型 400（平台前置校验 A0420）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocCodeFilesRequest(
        String project,
        String type) {
}
