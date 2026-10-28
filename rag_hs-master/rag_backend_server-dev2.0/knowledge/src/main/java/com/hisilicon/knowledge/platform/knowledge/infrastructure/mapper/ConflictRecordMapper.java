package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecord;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：PO ↔ Domain Entity（冲突记录）。
 */
@Mapper
public interface ConflictRecordMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecordId.of(po.id))")
    @Mapping(target = "firstJobId", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId.of(po.firstJobId))")
    @Mapping(target = "lastSeenJobId", expression = "java(po.lastSeenJobId == null ? null : com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId.of(po.lastSeenJobId))")
    @Mapping(target = "type", expression = "java(po.type == null ? null : com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictType.valueOf(po.type))")
    @Mapping(target = "severity", expression = "java(po.severity == null ? com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSeverity.MEDIUM : com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSeverity.valueOf(po.severity))")
    @Mapping(target = "badcaseStatus", expression = "java(po.badcaseStatus == null ? com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictBadcaseStatus.NONE : com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictBadcaseStatus.valueOf(po.badcaseStatus))")
    ConflictRecord toDomain(ConflictRecordPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    @Mapping(target = "firstJobId", expression = "java(entity.getFirstJobId().value())")
    @Mapping(target = "lastSeenJobId", expression = "java(entity.getLastSeenJobId() == null ? null : entity.getLastSeenJobId().value())")
    @Mapping(target = "type", expression = "java(entity.getType() == null ? null : entity.getType().name())")
    @Mapping(target = "severity", expression = "java(entity.getSeverity() == null ? com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSeverity.MEDIUM.name() : entity.getSeverity().name())")
    @Mapping(target = "badcaseStatus", expression = "java(entity.getBadcaseStatus() == null ? com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictBadcaseStatus.NONE.name() : entity.getBadcaseStatus().name())")
    ConflictRecordPO toPO(ConflictRecord entity);
}
