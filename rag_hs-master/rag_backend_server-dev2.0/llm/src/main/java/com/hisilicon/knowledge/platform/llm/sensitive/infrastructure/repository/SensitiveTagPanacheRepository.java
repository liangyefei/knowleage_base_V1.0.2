package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveTagPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 敏感词标签数据访问层（kb_sensitive_tags）
 */
@ApplicationScoped
public class SensitiveTagPanacheRepository implements PanacheRepositoryBase<SensitiveTagPO, String> {
}
