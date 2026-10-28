package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.BadcaseHandlingPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（Badcase 处理记录）。
 */
@ApplicationScoped
public class BadcaseHandlingPanacheRepository implements PanacheRepositoryBase<BadcaseHandlingPO, String> {
}
