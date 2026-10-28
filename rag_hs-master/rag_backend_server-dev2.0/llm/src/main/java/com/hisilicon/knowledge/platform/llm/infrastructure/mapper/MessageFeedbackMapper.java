package com.hisilicon.knowledge.platform.llm.infrastructure.mapper;

import com.hisilicon.knowledge.platform.llm.domain.entity.MessageFeedbackEntity;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.MessageFeedbackPO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 回答反馈 PO ↔ 领域实体映射
 */
@Mapper
public interface MessageFeedbackMapper {

    MessageFeedbackMapper INSTANCE = Mappers.getMapper(MessageFeedbackMapper.class);

    MessageFeedbackEntity toDomain(MessageFeedbackPO po);

    MessageFeedbackPO toPO(MessageFeedbackEntity entity);
}
