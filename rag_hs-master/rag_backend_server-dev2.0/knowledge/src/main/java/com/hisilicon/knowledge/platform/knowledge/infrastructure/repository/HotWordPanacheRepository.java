package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.HotWordPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（热门词）
 */
@ApplicationScoped
public class HotWordPanacheRepository implements PanacheRepositoryBase<HotWordPO, String> {
}
