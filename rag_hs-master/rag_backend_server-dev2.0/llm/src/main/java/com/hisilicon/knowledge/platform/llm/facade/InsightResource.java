package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.llm.command.InsightCommandService;
import com.hisilicon.knowledge.platform.llm.query.InsightQueryService;
import com.hisilicon.knowledge.platform.llm.query.dto.InsightListItem;
import com.hisilicon.knowledge.platform.llm.query.dto.InsightSettingsDTO;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 问答智能洞察 REST 接口。
 */
@Path("/llm/insights")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InsightResource {

    private static final Logger logger = LoggerFactory.getLogger(InsightResource.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Context
    HttpHeaders httpHeaders;

    private final InsightQueryService queryService;
    private final InsightCommandService commandService;

    @Inject
    public InsightResource(InsightQueryService queryService, InsightCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    /**
     * 分页查询洞察。
     * @param statusFilter ALL=全部 / ANALYZED=已智能分析（默认）/ PENDING=未智能分析
     */
    @GET
    public Uni<ResponseResult<PageResult<InsightListItem>>> list(
            @QueryParam("keyword") String keyword,
            @QueryParam("sensitive") String sensitive,
            @QueryParam("category") String category,
            @QueryParam("confidenceMin") Integer confidenceMin,
            @QueryParam("confidenceMax") Integer confidenceMax,
            @QueryParam("rating") String rating,
            @QueryParam("statusFilter") String statusFilter,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return queryService.listInsights(keyword, sensitive, category, confidenceMin, confidenceMax,
                        rating, statusFilter, page, pageSize)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 手动触发分析（异步：立即返回，后台逐条调模型分类）。
     */
    @POST
    @Path("/analyze")
    public Uni<ResponseResult<Map<String, Object>>> analyze(Map<String, String> body) {
        String startTimeStr = body == null ? null : body.get("startTime");
        String endTimeStr = body == null ? null : body.get("endTime");
        LocalDateTime startTime = parse(startTimeStr);
        LocalDateTime endTime = parse(endTimeStr);
        logger.info("Manual insight analyze triggered (async): startTime={}, endTime={}", startTime, endTime);
        commandService.batchAnalyze(startTime, endTime)
                .subscribe().with(
                        result -> logger.info("Manual insight analyze done: scanned={}, analyzed={}, failed={}",
                                result.scanned(), result.analyzed(), result.failed()),
                        err -> logger.warn("Manual insight analyze failed: {}", err.getMessage())
                );
        return Uni.createFrom().item(ResponseResult.ok(
                Map.of("started", true, "message", "分析任务已在后台执行"), locale()));
    }

    /**
     * 查定时设置。
     */
    @GET
    @Path("/settings")
    public Uni<ResponseResult<InsightSettingsDTO>> getSettings() {
        return queryService.getSettings()
                .map(dto -> ResponseResult.ok(dto, locale()));
    }

    /**
     * 更新定时配置。
     * body: { scheduleType, scheduleTime, scheduleDay? }
     *   - DAILY: 只需 scheduleTime (HH:mm)
     *   - WEEKLY: scheduleDay=1~7（周一~周日）
     *   - MONTHLY: scheduleDay=1~31
     */
    @PUT
    @Path("/settings")
    public Uni<ResponseResult<Void>> updateSettings(Map<String, Object> body) {
        String type = body == null ? null : (String) body.get("scheduleType");
        String time = body == null ? null : (String) body.get("scheduleTime");
        Integer day = null;
        if (body != null && body.get("scheduleDay") != null) {
            day = ((Number) body.get("scheduleDay")).intValue();
        }
        return commandService.updateSchedule(type, time, day)
                .map(v -> ResponseResult.ok(null, locale()));
    }

    /**
     * 提交洞察 badcase 工单。
     */
    @POST
    @Path("/{id}/badcase")
    public Uni<ResponseResult<String>> submitBadcase(
            @jakarta.ws.rs.PathParam("id") String insightId,
            Map<String, String> body) {
        String problemType = body == null ? null : body.get("problemType");
        String description = body == null ? null : body.get("description");
        String account = httpHeaders.getHeaderString("X-Account");
        if (account == null || account.isBlank()) account = "anonymous";
        return commandService.submitBadcase(new com.hisilicon.knowledge.platform.llm.command.InsightBadcaseCommand(
                        insightId, problemType, description), account)
                .map(ticketNo -> ResponseResult.ok(ticketNo, locale()));
    }

    private String locale() {
        return httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
    }

    private static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, DT_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
