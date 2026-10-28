package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryMetadataPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 类别元数据数据访问层
 */
@ApplicationScoped
public class CategoryMetadataPanacheRepository implements PanacheRepositoryBase<CategoryMetadataPO, String> {
}
