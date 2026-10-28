package com.hisilicon.knowledge.platform.system.infrastructure.mapper;

import com.hisilicon.knowledge.platform.system.domain.entity.McpToken;
import com.hisilicon.knowledge.platform.system.infrastructure.po.McpTokenPO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct 转换器：{@code mcp_tokens} 的 PO ↔ 领域实体。
 *
 * <p>字段名两侧一致，无需逐个 {@code @Mapping}；jti 是字符串主键，不做值对象包装。</p>
 *
 * <p>{@code lastUsedAt} 是库内的预留列：网关每请求只写 Redis（{@code mcp:token-lastused:{jti}}），
 * 不回写数据库（高频写放大换不来精度）。领域实体不持有该字段，写出时显式忽略，
 * 否则 MapStruct 会报 "Unmapped target property" 警告。</p>
 *
 * @author hisilicon
 */
@Mapper
public interface McpTokenMapper {

    /**
     * PO 转领域实体。
     *
     * @param po 持久化对象
     * @return 领域实体；入参为 null 时返回 null
     */
    McpToken toDomain(McpTokenPO po);

    /**
     * 领域实体转 PO（新增写出用）。
     *
     * @param token 领域实体
     * @return 持久化对象；入参为 null 时返回 null
     */
    @Mapping(target = "lastUsedAt", ignore = true)
    McpTokenPO toPO(McpToken token);
}