package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.infrastructure.po.InsightBadcaseTicketPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 洞察 badcase 工单 Panache 仓储（llm 侧轻量映射，不依赖 knowledge 模块）。
 */
@ApplicationScoped
public class InsightBadcasePanacheRepository implements PanacheRepositoryBase<InsightBadcaseTicketPO, String> {
}
