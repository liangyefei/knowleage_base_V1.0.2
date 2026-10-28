package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 字典缓存管理接口
 * <p>直接改库（绕过应用层 CRUD）后，调用本接口手动失效字典缓存，
 * 下次读取自动回源 DB 重建。{@link DictMetaKeyCacheService#evictCache()}
 * 同时删除 key 白名单缓存（{@code dict:active-meta-keys}）与值缓存
 * （{@code dict:active-meta-key-values}）。</p>
 */
@Path("/system/dict/cache")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DictCacheResource {

    private static final Logger logger = LoggerFactory.getLogger(DictCacheResource.class);

    private final DictMetaKeyCacheService dictMetaKeyCacheService;

    @Context
    HttpHeaders httpHeaders;

    public DictCacheResource(DictMetaKeyCacheService dictMetaKeyCacheService) {
        this.dictMetaKeyCacheService = dictMetaKeyCacheService;
    }

    /**
     * 手动失效字典缓存（key 白名单 + 值缓存）
     * <p>适用场景：直接 SQL 修改 kb_dict_types / kb_dict_items 后调用，
     * 避免等待 24 小时 TTL 过期。下次读取时自动回源 DB 重建缓存。</p>
     */
    @POST
    @Path("/refresh")
    public Uni<ResponseResult<Void>> refresh() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Manually evicting dict cache");
        return dictMetaKeyCacheService.evictCache()
                .map(v -> ResponseResult.ok(null, locale));
    }
}
