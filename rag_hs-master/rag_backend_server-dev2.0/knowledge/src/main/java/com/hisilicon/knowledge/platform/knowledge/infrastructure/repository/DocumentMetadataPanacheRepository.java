package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentMetadataPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 文档元数据数据访问层
 */
@ApplicationScoped
public class DocumentMetadataPanacheRepository implements PanacheRepositoryBase<DocumentMetadataPO, String> {
}
