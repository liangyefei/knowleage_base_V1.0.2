package com.hisilicon.knowledge.platform.llm.infrastructure.mapper;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.ConversationPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 会话 PO ↔ 领域实体映射
 */
@Mapper
public interface ConversationMapper {

    ConversationMapper INSTANCE = Mappers.getMapper(ConversationMapper.class);

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId.of(po.getId()))")
    ConversationEntity toDomain(ConversationPO po);

    @Mapping(target = "id", expression = "java(entity.getId() != null ? entity.getId().value() : null)")
    ConversationPO toPO(ConversationEntity entity);
}
