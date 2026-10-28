package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatch;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.ImportBatchPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：ImportBatch PO ↔ Domain Entity
 * <p>id 值对象用表达式；draftConflictPolicy 枚举↔String、defaultMetadata {@code Map<String,String>} 自动转换。</p>
 */
@Mapper
public interface ImportBatchMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchId.of(po.getId()))")
    ImportBatch toDomain(ImportBatchPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    ImportBatchPO toPO(ImportBatch entity);
}
