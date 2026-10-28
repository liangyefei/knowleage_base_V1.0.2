package com.hisilicon.knowledge.platform.llm.infrastructure.mapper;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.ConversationMessagePO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 对话消息 PO ↔ 领域实体映射
 */
@Mapper
public interface ConversationMessageMapper {

    ConversationMessageMapper INSTANCE = Mappers.getMapper(ConversationMessageMapper.class);

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageId.of(po.getId()))")
    @Mapping(target = "conversationId", expression = "java(com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId.of(po.getConversationId()))")
    ConversationMessageEntity toDomain(ConversationMessagePO po);

    @Mapping(target = "id", expression = "java(entity.getId() != null ? entity.getId().value() : null)")
    @Mapping(target = "conversationId", expression = "java(entity.getConversationId() != null ? entity.getConversationId().value() : null)")
    ConversationMessagePO toPO(ConversationMessageEntity entity);
}
