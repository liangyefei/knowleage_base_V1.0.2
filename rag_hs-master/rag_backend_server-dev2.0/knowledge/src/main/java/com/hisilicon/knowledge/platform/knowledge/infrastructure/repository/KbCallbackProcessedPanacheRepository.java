package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KbCallbackProcessedPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 回调幂等去重 Panache 数据访问层
 */
@ApplicationScoped
public class KbCallbackProcessedPanacheRepository
        implements PanacheRepositoryBase<KbCallbackProcessedPO, String> {
}
