package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.knowledge.dict.command.ChangeDictTypeStatusCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.command.CreateDictTypeCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.command.DeleteDictTypeCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.command.DictTypeCommandService;
import com.hisilicon.knowledge.platform.knowledge.dict.command.EditDictTypeCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictTypeEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.query.DictTypeQueryService;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictTypeItem;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 字典类型 REST 接口（系统字典管理）
 */
@Path("/system/dict/types")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DictTypeResource {

    private static final Logger logger = LoggerFactory.getLogger(DictTypeResource.class);

    private final DictTypeCommandService dictTypeCommandService;
    private final DictTypeQueryService dictTypeQueryService;

    @Context
    HttpHeaders httpHeaders;

    public DictTypeResource(DictTypeCommandService dictTypeCommandService,
                            DictTypeQueryService dictTypeQueryService) {
        this.dictTypeCommandService = dictTypeCommandService;
        this.dictTypeQueryService = dictTypeQueryService;
    }

    /**
     * 字典类型分页查询（可按状态筛选、关键字匹配 typeCode/typeName）
     * <p>page 默认 1，pageSize 默认 20（上限 1000）；status 为空 = 全部状态。</p>
     */
    @GET
    @Path("/search")
    public Uni<ResponseResult<PageResult<DictTypeItem>>> search(
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize,
            @QueryParam("keyword") String keyword,
            @QueryParam("status") Short status) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Dict type search request, page={}, pageSize={}, keyword={}, status={}", page, pageSize, keyword, status);
        return dictTypeQueryService.search(page, pageSize, keyword, status)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 创建字典类型
     */
    @POST
    @Path("/create")
    public Uni<ResponseResult<DictTypeItem>> create(@Valid @NotNull(message = "{app.common.body.notnull}") CreateDictTypeCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Create dict type request, typeCode={}", command.typeCode());
        return dictTypeCommandService.create(command, locale)
                .map(entity -> ResponseResult.ok(toItem(entity), locale));
    }

    /**
     * 编辑字典类型（全量替换；typeCode 可编辑，级联更新条目）
     */
    @POST
    @Path("/edit")
    public Uni<ResponseResult<Void>> edit(@Valid @NotNull(message = "{app.common.body.notnull}") EditDictTypeCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Edit dict type request, id={}", command.id());
        return dictTypeCommandService.edit(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除字典类型（软删，级联软删该类型下全部条目）
     */
    @POST
    @Path("/delete")
    public Uni<ResponseResult<Void>> delete(@Valid @NotNull(message = "{app.common.body.notnull}") DeleteDictTypeCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Delete dict type request, id={}", command.id());
        return dictTypeCommandService.delete(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 切换字典类型启用/禁用状态（1=启用，2=禁用）
     */
    @POST
    @Path("/changeStatus")
    public Uni<ResponseResult<Void>> changeStatus(@Valid @NotNull(message = "{app.common.body.notnull}") ChangeDictTypeStatusCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Change dict type status request, id={}, status={}", command.id(), command.status());
        return dictTypeCommandService.changeStatus(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    private DictTypeItem toItem(DictTypeEntity entity) {
        return new DictTypeItem(entity.getId().value(), entity.getTypeCode(), entity.getTypeName(),
                entity.getControlledMetaKeys(), entity.getStatus(), entity.getIsPreset(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
