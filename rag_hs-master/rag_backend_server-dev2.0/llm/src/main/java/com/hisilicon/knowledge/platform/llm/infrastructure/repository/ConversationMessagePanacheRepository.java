package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.infrastructure.po.ConversationMessagePO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 对话消息 Panache 仓储
 */
@ApplicationScoped
public class ConversationMessagePanacheRepository implements PanacheRepositoryBase<ConversationMessagePO, String> {
}
