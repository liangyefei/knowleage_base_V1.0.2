package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 文档版本 Panache 数据访问层
 */
@ApplicationScoped
public class DocumentVersionPanacheRepository
        implements PanacheRepositoryBase<DocumentVersionPO, DocumentVersionPO.DocumentVersionPK> {
}
