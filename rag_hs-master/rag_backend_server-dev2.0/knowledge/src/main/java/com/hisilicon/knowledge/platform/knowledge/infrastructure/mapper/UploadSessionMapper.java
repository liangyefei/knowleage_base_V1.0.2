package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.UploadSessionPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：UploadSession PO ↔ Domain Entity
 * <p>id 值对象用表达式；draftPolicy/status 枚举↔String 由 MapStruct 自动按 name 转换；
 * metadata {@code Map<String,String>} 直接拷贝（PO 侧由 @JdbcTypeCode(SqlTypes.JSON) 落 jsonb）。</p>
 */
@Mapper
public interface UploadSessionMapper {

    @Mapping(target = "id", expression = "java(com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionId.of(po.getId()))")
    UploadSession toDomain(UploadSessionPO po);

    @Mapping(target = "id", expression = "java(entity.getId().value())")
    UploadSessionPO toPO(UploadSession entity);
}
