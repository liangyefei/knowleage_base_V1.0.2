package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层
 */
@ApplicationScoped
public class CategoryPanacheRepository implements PanacheRepositoryBase<CategoryPO, String> {
}
