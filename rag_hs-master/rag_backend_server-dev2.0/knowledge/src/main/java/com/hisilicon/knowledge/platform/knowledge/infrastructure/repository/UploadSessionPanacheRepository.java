package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.UploadSessionPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * tus 上传会话 Panache 仓储
 */
@ApplicationScoped
public class UploadSessionPanacheRepository implements PanacheRepositoryBase<UploadSessionPO, String> {
}
