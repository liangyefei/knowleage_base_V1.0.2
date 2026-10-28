package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.infrastructure.po.ConversationPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 会话 Panache 仓储
 */
@ApplicationScoped
public class ConversationPanacheRepository implements PanacheRepositoryBase<ConversationPO, String> {
}
