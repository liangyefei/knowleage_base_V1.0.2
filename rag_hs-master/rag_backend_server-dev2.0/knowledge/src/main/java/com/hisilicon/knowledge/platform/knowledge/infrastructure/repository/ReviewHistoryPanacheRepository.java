package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.ReviewHistoryPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 审核历史 Panache 数据访问层
 */
@ApplicationScoped
public class ReviewHistoryPanacheRepository implements PanacheRepositoryBase<ReviewHistoryPO, String> {
}
