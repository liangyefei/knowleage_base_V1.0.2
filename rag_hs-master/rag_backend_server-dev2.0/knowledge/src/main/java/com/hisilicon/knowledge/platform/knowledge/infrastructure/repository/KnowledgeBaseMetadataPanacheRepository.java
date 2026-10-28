package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBaseMetadataPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 知识库元数据数据访问层
 */
@ApplicationScoped
public class KnowledgeBaseMetadataPanacheRepository implements PanacheRepositoryBase<KnowledgeBaseMetadataPO, String> {
}
