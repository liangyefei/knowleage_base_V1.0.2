package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.infrastructure.po.MessageFeedbackPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 回答反馈 Panache 仓储
 */
@ApplicationScoped
public class MessageFeedbackPanacheRepository implements PanacheRepositoryBase<MessageFeedbackPO, String> {
}
