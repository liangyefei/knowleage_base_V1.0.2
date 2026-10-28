package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.ConflictRecordCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.ConflictScanCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.StartConflictScanCommand;
import com.hisilicon.knowledge.platform.knowledge.command.SubmitConflictBadcaseCommand;
import com.hisilicon.knowledge.platform.knowledge.command.UpdateAutoDetectionCommand;
import com.hisilicon.knowledge.platform.knowledge.query.ConflictQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.AutoDetectionStatusDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ConflictDetailDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ConflictJobProgress;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ConflictListItem;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
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
 * 冲突检测 REST 接口。
 * <p>前缀 {@code /kb/conflicts}。需求简化：无状态流转（resolve/ignore）接口。</p>
 */
@Path("/kb/conflicts")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class ConflictResource {

    private static final Logger logger = LoggerFactory.getLogger(ConflictResource.class);

    private final ConflictScanCommandService scanCommandService;
    private final ConflictRecordCommandService recordCommandService;
    private final ConflictQueryService queryService;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    public ConflictResource(ConflictScanCommandService scanCommandService,
                            ConflictRecordCommandService recordCommandService,
                            ConflictQueryService queryService) {
        this.scanCommandService = scanCommandService;
        this.recordCommandService = recordCommandService;
        this.queryService = queryService;
    }

    /**
     * 查自动检测开关状态。不带 kbId = 全局开关；带 kbId = 该 KB 的覆盖开关。
     */
    @GET
    @Path("/settings/auto-detection")
    public Uni<ResponseResult<AutoDetectionStatusDTO>> getAutoDetection(@QueryParam("kbId") String kbId) {
        String operatorId = operatorIdHolder.get();
        logger.info("Get auto-detection status, kbId={}, operatorId={}", kbId, operatorId);
        return queryService.getAutoDetectionStatus(kbId)
                .map(dto -> ResponseResult.ok(dto, locale()));
    }

    /**
     * 切换自动检测开关。body 里可带 kbId：不带=全局，带=该 KB 覆盖开关。
     */
    @POST
    @Path("/settings/auto-detection")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<AutoDetectionStatusDTO>> updateAutoDetection(
            @Valid @NotNull UpdateAutoDetectionCommand command) {
        String operatorId = operatorIdHolder.get();
        logger.info("Update auto-detection, enabled={}, kbId={}, operatorId={}",
                command.enabled(), command.kbId(), operatorId);
        Uni<com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSettings> uni =
                (command.kbId() == null || command.kbId().isBlank())
                ? recordCommandService.updateAutoDetection(operatorId, command.enabled())
                : recordCommandService.updateKbAutoDetection(command.kbId().trim(), operatorId, command.enabled());
        return uni
                .map(s -> new AutoDetectionStatusDTO(s.isAutoEnabled(), s.getUpdatedAt()))
                .map(dto -> ResponseResult.ok(dto, locale()));
    }

    /**
     * 发起手动扫描（立即检测）。
     */
    @POST
    @Path("/start-scan")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<ConflictScanCommandService.StartScanResult>> startScan(
            @Valid @NotNull StartConflictScanCommand command) {
        String operatorId = operatorIdHolder.get();
        logger.info("Start manual conflict scan, kbId={}, documentIds={}, categoryIds={}, operatorId={}",
                command.kbId(),
                command.documentIds() == null ? "null" : command.documentIds().size() + " items",
                command.categoryIds() == null ? "null" : command.categoryIds().size() + " items",
                operatorId);
        return scanCommandService.startManualScan(operatorId, command)
                .map(result -> ResponseResult.ok(result, locale()));
    }

    /**
     * 轮询任务进度。
     */
    @GET
    @Path("/jobs/{jobId}")
    public Uni<ResponseResult<ConflictJobProgress>> getJobProgress(@PathParam("jobId") String jobId) {
        logger.info("Get conflict scan job progress, jobId={}", jobId);
        return queryService.getJobProgress(jobId)
                .map(p -> ResponseResult.ok(p, locale()));
    }

    /**
     * 分页查询冲突列表（keyword / type / kbId / badcaseSubmitted 全部可选）。
     */
    @GET
    public Uni<ResponseResult<PageResult<ConflictListItem>>> list(
            @QueryParam("keyword") String keyword,
            @QueryParam("type") String type,
            @QueryParam("kbId") String kbId,
            @QueryParam("badcaseSubmitted") Boolean badcaseSubmitted,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String operatorId = operatorIdHolder.get();
        logger.info("Query conflict list, keyword={}, type={}, kbId={}, badcaseSubmitted={}, operatorId={}",
                keyword, type, kbId, badcaseSubmitted, operatorId);
        return queryService.list(keyword, type, kbId, badcaseSubmitted, page, pageSize)
                .map(p -> ResponseResult.ok(p, locale()));
    }

    /**
     * 查冲突详情（基础信息 + 证据卡片 + badcase 信息）。
     */
    @GET
    @Path("/{id}")
    public Uni<ResponseResult<ConflictDetailDTO>> getDetail(@PathParam("id") String id) {
        logger.info("Get conflict detail, id={}", id);
        return queryService.getDetail(id)
                .map(d -> ResponseResult.ok(d, locale()));
    }

    /**
     * 提交 Badcase 工单。
     */
    @POST
    @Path("/{id}/badcase")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<BadcaseSubmitResult>> submitBadcase(
            @PathParam("id") String id,
            @Valid @NotNull SubmitConflictBadcaseCommand command) {
        String operatorId = operatorIdHolder.get();
        logger.info("Submit conflict badcase, id={}, operatorId={}", id, operatorId);
        // 用 URL 路径里的 id 覆盖 body 里可能传的 recordId（body 里不传也行）
        SubmitConflictBadcaseCommand actualCommand = new SubmitConflictBadcaseCommand(id, command.description());
        return recordCommandService.submitBadcase(operatorId, actualCommand)
                .map(ticketId -> ResponseResult.ok(new BadcaseSubmitResult(ticketId), locale()));
    }

    private String locale() {
        return httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
    }

    /**
     * Badcase 提交返回。
     */
    public record BadcaseSubmitResult(String ticketId) {
    }
}
