package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.AssignBadcaseCommand;
import com.hisilicon.knowledge.platform.knowledge.command.BadcaseCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.CreateBadcaseCommand;
import com.hisilicon.knowledge.platform.knowledge.command.HandleBadcaseCommand;
import com.hisilicon.knowledge.platform.knowledge.query.BadcaseQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.AssigneeUserDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BadcaseDetailDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BadcaseDictDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BadcaseListItem;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Badcase 管理 REST 接口。
 * <p>前缀 {@code /kb/badcases}。</p>
 */
@Path("/kb/badcases")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class BadcaseResource {

    private static final Logger logger = LoggerFactory.getLogger(BadcaseResource.class);

    private final BadcaseCommandService commandService;
    private final BadcaseQueryService queryService;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    public BadcaseResource(BadcaseCommandService commandService,
                           BadcaseQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /**
     * 字典接口（来源 / 问题类型映射 / 状态）。
     */
    @GET
    @Path("/dict")
    public Uni<ResponseResult<BadcaseDictDTO>> dict() {
        logger.info("Get badcase dict");
        return Uni.createFrom().item(queryService.dict())
                .map(dto -> ResponseResult.ok(dto, locale()));
    }

    /**
     * 分页列表（来源 / 问题类型 / 状态 / 知识库 / 关键词 全部可选）。
     */
    @GET
    public Uni<ResponseResult<PageResult<BadcaseListItem>>> list(
            @QueryParam("source") String source,
            @QueryParam("problemType") String problemType,
            @QueryParam("status") String status,
            @QueryParam("kbId") String kbId,
            @QueryParam("keyword") String keyword,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        logger.info("Query badcase list, source={}, problemType={}, status={}, kbId={}, keyword={}",
                source, problemType, status, kbId, keyword);
        return queryService.list(source, problemType, status, kbId, keyword, page, pageSize)
                .map(result -> ResponseResult.ok(result, locale()));
    }

    /**
     * 详情。
     */
    @GET
    @Path("/{id}")
    public Uni<ResponseResult<BadcaseDetailDTO>> detail(@PathParam("id") String id) {
        logger.info("Get badcase detail, id={}", id);
        return queryService.detail(id)
                .map(dto -> ResponseResult.ok(dto, locale()));
    }

    /**
     * 创建 Badcase 工单。
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<CreateResult>> create(
            @Valid @NotNull CreateBadcaseCommand command) {
        String operatorId = operatorIdHolder.get();
        logger.info("Create badcase, source={}, operatorId={}", command.source(), operatorId);
        return commandService.create(operatorId, command)
                .map(ticketId -> ResponseResult.ok(new CreateResult(ticketId), locale()));
    }

    /**
     * 统一处理：修改状态 + 填写备注。
     */
    @POST
    @Path("/{id}/handle")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<HandleResult>> handle(
            @PathParam("id") String id,
            @Valid @NotNull HandleBody body) {
        String operatorId = operatorIdHolder.get();
        logger.info("Handle badcase, id={}, targetStatus={}, operatorId={}",
                id, body.targetStatus(), operatorId);
        HandleBadcaseCommand command = new HandleBadcaseCommand(id, body.targetStatus(), body.remark());
        return commandService.handle(operatorId, null, command)
                .map(ticketId -> ResponseResult.ok(new HandleResult(ticketId), locale()));
    }

    /**
     * 移交处理人（不改状态）。
     */
    @POST
    @Path("/{id}/assign")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<HandleResult>> assign(
            @PathParam("id") String id,
            @Valid @NotNull AssignBody body) {
        logger.info("Assign badcase, id={}, handler={}", id, body.handler());
        AssignBadcaseCommand command = new AssignBadcaseCommand(
                id, body.handler(), body.handlerName(), body.remark());
        return commandService.assign(command)
                .map(ticketId -> ResponseResult.ok(new HandleResult(ticketId), locale()));
    }

    /**
     * 搜索可选处理人（按账号/姓名模糊）。
     */
    @GET
    @Path("/handlers/search")
    public Uni<ResponseResult<java.util.List<AssigneeUserDTO>>> searchAssignees(
            @QueryParam("keyword") String keyword) {
        return queryService.searchAssignees(keyword)
                .map(list -> ResponseResult.ok(list, locale()));
    }

    private String locale() {
        return httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
    }

    /** 创建返回。 */
    public record CreateResult(String ticketId) {
    }

    /** 处理返回。 */
    public record HandleResult(String ticketId) {
    }

    /** 处理请求体（ticketId 由 URL 覆盖）。 */
    public record HandleBody(@NotBlank String targetStatus, @NotBlank String remark) {
    }

    /** 移交请求体。 */
    public record AssignBody(@NotBlank String handler, String handlerName, String remark) {
    }
}
