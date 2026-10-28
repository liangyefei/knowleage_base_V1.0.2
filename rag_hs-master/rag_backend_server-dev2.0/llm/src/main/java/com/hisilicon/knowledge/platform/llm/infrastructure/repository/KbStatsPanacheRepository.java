package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.infrastructure.po.KbStatsPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 问答 KB 统计 Panache 仓储
 */
@ApplicationScoped
public class KbStatsPanacheRepository implements PanacheRepositoryBase<KbStatsPO, String> {
}
