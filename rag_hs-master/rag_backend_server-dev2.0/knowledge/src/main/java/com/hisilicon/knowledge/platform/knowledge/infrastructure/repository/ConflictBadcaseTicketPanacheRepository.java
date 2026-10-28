package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictBadcaseTicketPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（Badcase 工单）。
 */
@ApplicationScoped
public class ConflictBadcaseTicketPanacheRepository implements PanacheRepositoryBase<ConflictBadcaseTicketPO, String> {
}
