package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import io.smallrye.mutiny.Uni;

import java.util.Map;

/**
 * 类别元数据仓储接口（category_metadata 表）
 * <p>key-value 扩展模式，存储类别维度的扩展属性（后续用于权限控制）。</p>
 */
public interface CategoryMetadataRepository {

    /**
     * 全量替换类别元数据
     *
     * @param categoryId 类别 ID
     * @param metadata   meta_key → meta_value 映射（空 Map 或 null = 清空）
     * @return 完成信号
     */
    Uni<Void> replaceAll(String categoryId, Map<String, String> metadata);
}
