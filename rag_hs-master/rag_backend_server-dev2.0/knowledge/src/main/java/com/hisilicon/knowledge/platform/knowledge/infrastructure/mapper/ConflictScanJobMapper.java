package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictScanJobPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：PO ↔ Domain Entity（冲突检测任务）。
 */
@Mapper
public interface ConflictScanJobMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId.of(po.id))")
    @Mapping(target = "triggerType", expression = "java(po.triggerType == null ? null : com.hisilicon.knowledge.platform.knowledge.domain.entity.ScanTriggerType.valueOf(po.triggerType))")
    @Mapping(target = "mode", expression = "java(po.mode == null ? null : com.hisilicon.knowledge.platform.knowledge.domain.entity.ScanMode.valueOf(po.mode))")
    @Mapping(target = "status", expression = "java(po.status == null ? null : com.hisilicon.knowledge.platform.knowledge.domain.entity.ScanJobStatus.valueOf(po.status))")
    ConflictScanJob toDomain(ConflictScanJobPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    @Mapping(target = "triggerType", expression = "java(entity.getTriggerType() == null ? null : entity.getTriggerType().name())")
    @Mapping(target = "mode", expression = "java(entity.getMode() == null ? null : entity.getMode().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus() == null ? null : entity.getStatus().name())")
    ConflictScanJobPO toPO(ConflictScanJob entity);
}
