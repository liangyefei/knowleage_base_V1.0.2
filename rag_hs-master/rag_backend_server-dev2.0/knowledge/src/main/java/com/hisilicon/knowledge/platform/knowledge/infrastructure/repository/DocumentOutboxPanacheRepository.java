package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentOutboxPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 事务发件箱数据访问层
 */
@ApplicationScoped
public class DocumentOutboxPanacheRepository implements PanacheRepositoryBase<DocumentOutboxPO, String> {
}
