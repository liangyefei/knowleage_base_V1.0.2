package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.infrastructure.po.QaInsightPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 问答洞察 Panache 仓储。
 */
@ApplicationScoped
public class QaInsightPanacheRepository implements PanacheRepositoryBase<QaInsightPO, String> {
}
