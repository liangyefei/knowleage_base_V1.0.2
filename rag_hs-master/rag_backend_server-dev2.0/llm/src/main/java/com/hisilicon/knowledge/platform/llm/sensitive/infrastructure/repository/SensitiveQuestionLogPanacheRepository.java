package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveQuestionLogPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 不合规提问记录数据访问层（kb_sensitive_question_logs）
 */
@ApplicationScoped
public class SensitiveQuestionLogPanacheRepository implements PanacheRepositoryBase<SensitiveQuestionLogPO, String> {
}
