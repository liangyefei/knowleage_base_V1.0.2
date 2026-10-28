package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 文档元数据下拉选项分组 DTO
 *
 * @param metaKey 元数据键（document_metadata.meta_key，如 chip_model / version / product）
 * @param label   前端显示名（国际化，取不到时回退为 metaKey 原值）
 * @param values  去重后的可选值列表（逗号分隔的多值已拆分为原子值，按字典序）
 */
public record MetadataOptionGroup(String metaKey, String label, List<String> values) {
}
