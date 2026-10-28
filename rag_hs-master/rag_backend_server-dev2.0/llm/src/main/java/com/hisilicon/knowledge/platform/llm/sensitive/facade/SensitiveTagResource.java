package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.llm.sensitive.command.ChangeSensitiveTagStatusCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.CreateSensitiveTagCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.DeleteSensitiveTagCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.EditSensitiveTagCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.SensitiveTagCommandService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveTagQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagItem;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagStatistics;
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

import java.util.List;

/**
 * 敏感词标签 REST 接口（系统管理-敏感词管理）
 */
@Path("/system/sensitive/tags")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SensitiveTagResource {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveTagResource.class);

    private final SensitiveTagCommandService tagCommandService;
    private final SensitiveTagQueryService tagQueryService;

    @Context
    HttpHeaders httpHeaders;

    public SensitiveTagResource(SensitiveTagCommandService tagCommandService,
                                SensitiveTagQueryService tagQueryService) {
        this.tagCommandService = tagCommandService;
        this.tagQueryService = tagQueryService;
    }

    /**
     * 标签分页查询（可按关键字匹配文本标签/标识，按状态筛选；默认不含软删）
     */
    @GET
    @Path("/search")
    public Uni<ResponseResult<PageResult<SensitiveTagItem>>> search(
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize,
            @QueryParam("keyword") String keyword,
            @QueryParam("status") Short status) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Sensitive tag search request, page={}, pageSize={}, keyword={}, status={}", page, pageSize, keyword, status);
        return tagQueryService.search(page, pageSize, keyword, status)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 全部启用标签下拉列表
     */
    @GET
    @Path("/list")
    public Uni<ResponseResult<List<SensitiveTagItem>>> list() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return tagQueryService.listActive()
                .map(items -> ResponseResult.ok(items, locale));
    }

    /**
     * 分类统计（管理页侧边栏）：全部分类词数 + 每个分类词数
     */
    @GET
    @Path("/statistics")
    public Uni<ResponseResult<SensitiveTagStatistics>> statistics() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Sensitive tag statistics request");
        return tagQueryService.statistics()
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 创建标签
     */
    @POST
    @Path("/create")
    public Uni<ResponseResult<SensitiveTagItem>> create(@Valid @NotNull(message = "{app.common.body.notnull}") CreateSensitiveTagCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Create sensitive tag request, textLabel={}", command.textLabel());
        return tagCommandService.create(command, locale)
                .map(entity -> ResponseResult.ok(toItem(entity), locale));
    }

    /**
     * 编辑标签（全量替换）
     */
    @POST
    @Path("/edit")
    public Uni<ResponseResult<Void>> edit(@Valid EditSensitiveTagCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Edit sensitive tag request, id={}, textLabel={}", command.id(), command.textLabel());
        return tagCommandService.edit(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除标签（软删，级联删除该标签下全部词）
     */
    @POST
    @Path("/delete")
    public Uni<ResponseResult<Void>> delete(@Valid DeleteSensitiveTagCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Delete sensitive tag request, count={}", command.ids().size());
        return deleteAll(command.ids(), locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    private Uni<Void> deleteAll(List<String> ids, String locale) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String id : ids) {
            chain = chain.flatMap(v -> tagCommandService.delete(id, locale));
        }
        return chain;
    }

    /**
     * 切换标签启用/禁用状态（1=启用，2=禁用）
     */
    @POST
    @Path("/changeStatus")
    public Uni<ResponseResult<Void>> changeStatus(@Valid ChangeSensitiveTagStatusCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Change sensitive tag status request, id={}, status={}", command.id(), command.status());
        return tagCommandService.changeStatus(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    private SensitiveTagItem toItem(com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity entity) {
        return new SensitiveTagItem(entity.getId().value(), entity.getTextLabel(),
                entity.getLabelIdentification(), entity.getRemarks(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
