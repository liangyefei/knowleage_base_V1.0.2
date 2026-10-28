package com.hisilicon.knowledge.platform.knowledge.dict.query.dto;

import java.time.LocalDateTime;

/**
 * 字典类型列表项
 *
 * @param id                 主键
 * @param typeCode           类型编码
 * @param typeName           类型显示名
 * @param controlledMetaKeys 管控的 metadata 键名（逗号分隔）
 * @param status             0=删除, 1=启用, 2=禁用
 * @param isPreset           是否预置类型（true=系统初始化创建，不可删除）
 * @param createdAt          创建时间
 * @param updatedAt          更新时间
 */
public record DictTypeItem(
        String id,
        String typeCode,
        String typeName,
        String controlledMetaKeys,
        Short status,
        Boolean isPreset,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
