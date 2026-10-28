package com.hisilicon.knowledge.platform.llm.infrastructure.mapper;

import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.KbStatsPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 问答 KB 统计 PO ↔ 领域实体映射
 */
@Mapper
public interface KbStatsMapper {

    KbStatsMapper INSTANCE = Mappers.getMapper(KbStatsMapper.class);

    /**
     * PO → 领域实体
     * <p>{@code answerText} 不是 {@code qa_kb_stats} 的列（PO 无对应字段），而是仓储查询时
     * join {@code conversation_messages} 取 ASSISTANT 消息正文回填的展示字段，因此显式忽略：
     * 既表达"故意不映射"的意图，也消除 MapStruct 的 Unmapped target property 告警。</p>
     */
    @Mapping(target = "answerText", ignore = true)
    KbStatsEntity toDomain(KbStatsPO po);

    KbStatsPO toPO(KbStatsEntity entity);
}
