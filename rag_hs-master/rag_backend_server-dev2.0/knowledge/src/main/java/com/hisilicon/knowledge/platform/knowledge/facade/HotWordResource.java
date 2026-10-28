package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.ChangeHotWordStatusCommand;
import com.hisilicon.knowledge.platform.knowledge.command.CreateHotWordCommand;
import com.hisilicon.knowledge.platform.knowledge.command.HotWordCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.HotWordIdCommand;
import com.hisilicon.knowledge.platform.knowledge.command.UpdateHotWordCommand;
import com.hisilicon.knowledge.platform.knowledge.query.HotWordQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.HotWordItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.HotWordOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.HotWordStats;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
 * 热门词 REST 接口
 * <p>方法约定：list/listEnabled 用 GET，delete 用 DELETE，create/update/changeStatus 用 POST（JSON）。</p>
 */
@Path("/hotword")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class HotWordResource {

    private static final Logger logger = LoggerFactory.getLogger(HotWordResource.class);

    private final HotWordCommandService commandService;
    private final HotWordQueryService queryService;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    public HotWordResource(HotWordCommandService commandService, HotWordQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /**
     * 分页查询热门词（keyword 模糊 / kbId / status 全部可选）
     */
    @GET
    @Path("/list")
    public Uni<ResponseResult<PageResult<HotWordItem>>> list(
            @QueryParam("keyword") String keyword,
            @QueryParam("kbId") String kbId,
            @QueryParam("status") Short status,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 校验 status：不为空时只能为 0 或 1
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidStatusValue());
        }

        logger.info("Hot word page query request, keyword={}, kbId={}, status={}, page={}, pageSize={}, locale={}",
                keyword, kbId, status, page, pageSize, locale);

        return queryService.list(keyword, kbId, status, page, pageSize)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 查询启用中的热门词（跨所选知识库）
     * <p>总额度 = max(4, 所选知识库数)：名额尽量均衡（2 库各约 2 条），
     * 库数超过 4 时每库 1 条，总数可超过 4。</p>
     */
    @GET
    @Path("/listEnabled")
    public Uni<ResponseResult<List<HotWordOption>>> listEnabled(
            @QueryParam("kbIds") List<String> kbIds) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        if (kbIds == null || kbIds.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidParameter());
        }

        logger.info("Query enabled hot word options request, kbIds={}, locale={}", kbIds, locale);

        return queryService.listEnabled(kbIds)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 热门词统计（总数 / 启用中 / 已停用）
     * <p>kbIds 可多选：不传 = 全部；传了 = 只统计所选知识库。</p>
     */
    @GET
    @Path("/stats")
    public Uni<ResponseResult<HotWordStats>> stats(
            @QueryParam("kbIds") List<String> kbIds) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        logger.info("Hot word stats request, kbIds={}, locale={}", kbIds, locale);

        return queryService.stats(kbIds)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 创建热门词
     */
    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> create(
            @Valid @NotNull(message = "{app.common.body.notnull}") CreateHotWordCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String operatorId = operatorIdHolder.get();

        // 校验 status：只能为 0 或 1
        if (command.status() != 0 && command.status() != 1) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidStatusValue());
        }

        logger.info("Create hot word request, kbId={}, operatorId={}, locale={}", command.kbId(), operatorId, locale);

        return commandService.create(operatorId, command)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 更新热门词（内容 / 所属知识库 / 排序号）
     */
    @POST
    @Path("/update")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> update(
            @Valid @NotNull(message = "{app.common.body.notnull}") UpdateHotWordCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String operatorId = operatorIdHolder.get();

        // 校验 status：只能为 0 或 1
        if (command.status() != 0 && command.status() != 1) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidStatusValue());
        }

        logger.info("Update hot word request, id={}, kbId={}, operatorId={}, locale={}",
                command.id(), command.kbId(), operatorId, locale);

        return commandService.update(command)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除热门词（物理删除）
     */
    @DELETE
    @Path("/delete")
    public Uni<ResponseResult<Void>> delete(
            @QueryParam("id") @NotBlank(message = "{app.hotword.id.notblank}") String id) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();

        logger.info("Delete hot word request, id={}, operatorId={}, locale={}", id, operatorId, locale);

        return commandService.delete(new HotWordIdCommand(id))
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 变更热门词状态（启用/停用）
     */
    @POST
    @Path("/changeStatus")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> changeStatus(
            @Valid @NotNull(message = "{app.common.body.notnull}") ChangeHotWordStatusCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String operatorId = operatorIdHolder.get();

        // 校验 status：只能为 0 或 1
        if (command.status() != 0 && command.status() != 1) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidStatusValue());
        }

        logger.info("Change hot word status request, id={}, status={}, operatorId={}, locale={}",
                command.id(), command.status(), operatorId, locale);

        return commandService.changeStatus(command)
                .map(v -> ResponseResult.ok(null, locale));
    }
}
