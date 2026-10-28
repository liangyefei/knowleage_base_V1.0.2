package com.hisilicon.knowledge.platform.knowledge.dict.query.dto;

import java.time.LocalDateTime;

/**
 * 字典条目列表项
 *
 * @param id        主键
 * @param typeCode  所属字典类型编码
 * @param label     显示名称
 * @param value     存储值（写入 metadata 的实际值）
 * @param sortOrder 排序号
 * @param status    0=删除, 1=启用, 2=禁用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DictItemItem(
        String id,
        String typeCode,
        String label,
        String value,
        Integer sortOrder,
        Short status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
