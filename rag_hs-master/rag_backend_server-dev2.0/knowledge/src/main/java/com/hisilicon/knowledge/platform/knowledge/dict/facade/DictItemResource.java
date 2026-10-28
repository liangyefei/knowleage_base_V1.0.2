package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.knowledge.dict.command.ChangeDictItemStatusCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.command.CreateDictItemCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.command.DeleteDictItemCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.command.DictItemCommandService;
import com.hisilicon.knowledge.platform.knowledge.dict.command.EditDictItemCommand;
import com.hisilicon.knowledge.platform.knowledge.dict.domain.entity.DictItemEntity;
import com.hisilicon.knowledge.platform.knowledge.dict.query.DictItemQueryService;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictItemItem;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;

/**
 * 字典条目 REST 接口（系统字典管理）
 */
@Path("/system/dict/items")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DictItemResource {

    private static final Logger logger = LoggerFactory.getLogger(DictItemResource.class);

    private final DictItemCommandService dictItemCommandService;
    private final DictItemQueryService dictItemQueryService;

    @Context
    HttpHeaders httpHeaders;

    public DictItemResource(DictItemCommandService dictItemCommandService,
                            DictItemQueryService dictItemQueryService) {
        this.dictItemCommandService = dictItemCommandService;
        this.dictItemQueryService = dictItemQueryService;
    }

    /**
     * 字典条目查询（typeCode 必传，可按状态筛选；供下拉框使用）
     */
    @GET
    @Path("/search")
    public Uni<ResponseResult<List<DictItemItem>>> search(
            @QueryParam("typeCode") @NotBlank(message = "{app.dict.typeCode.notblank}") String typeCode,
            @QueryParam("status") Short status) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Dict item list request, typeCode={}, status={}", typeCode, status);
        return dictItemQueryService.list(typeCode, status)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 新增字典条目
     */
    @POST
    @Path("/create")
    public Uni<ResponseResult<DictItemItem>> create(@Valid @NotNull(message = "{app.common.body.notnull}") CreateDictItemCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Create dict item request, typeCode={}, value={}", command.typeCode(), command.value());
        return dictItemCommandService.create(command, locale)
                .map(entity -> ResponseResult.ok(toItem(entity), locale));
    }

    /**
     * 编辑字典条目（label/value 全量替换；sortOrder 不传 = 保持原值）
     */
    @POST
    @Path("/edit")
    public Uni<ResponseResult<Void>> edit(@Valid @NotNull(message = "{app.common.body.notnull}") EditDictItemCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Edit dict item request, id={}", command.id());
        return dictItemCommandService.edit(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除字典条目（软删；不影响已引用该值的历史元数据）
     */
    @POST
    @Path("/delete")
    public Uni<ResponseResult<Void>> delete(@Valid @NotNull(message = "{app.common.body.notnull}") DeleteDictItemCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Delete dict item request, id={}", command.id());
        return dictItemCommandService.delete(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 切换字典条目启用/禁用状态（1=启用，2=禁用）
     */
    @POST
    @Path("/changeStatus")
    public Uni<ResponseResult<Void>> changeStatus(@Valid @NotNull(message = "{app.common.body.notnull}") ChangeDictItemStatusCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Change dict item status request, id={}, status={}", command.id(), command.status());
        return dictItemCommandService.changeStatus(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    private DictItemItem toItem(DictItemEntity entity) {
        return new DictItemItem(entity.getId().value(), entity.getTypeCode(), entity.getLabel(),
                entity.getValue(), entity.getSortOrder(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
