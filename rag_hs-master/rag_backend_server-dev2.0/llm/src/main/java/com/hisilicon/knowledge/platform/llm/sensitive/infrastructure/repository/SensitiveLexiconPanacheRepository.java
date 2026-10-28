package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveLexiconPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 敏感词词库数据访问层（kb_sensitive_lexicons）
 */
@ApplicationScoped
public class SensitiveLexiconPanacheRepository implements PanacheRepositoryBase<SensitiveLexiconPO, String> {
}
