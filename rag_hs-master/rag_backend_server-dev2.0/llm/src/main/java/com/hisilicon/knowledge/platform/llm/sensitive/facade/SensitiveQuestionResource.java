package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveQuestionQuery;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveQuestionQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveQuestionItem;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 不合规提问记录 REST 接口（系统管理-敏感词管理）
 */
@Path("/system/sensitive/questions")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SensitiveQuestionResource {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveQuestionResource.class);

    private final SensitiveQuestionQueryService questionQueryService;

    @Context
    HttpHeaders httpHeaders;

    public SensitiveQuestionResource(SensitiveQuestionQueryService questionQueryService) {
        this.questionQueryService = questionQueryService;
    }

    /**
     * 分页查询不合规提问记录（可按账号模糊、标签标识、时间范围过滤，按请求时间倒序）
     *
     * @param beginTime ISO-8601（如 2026-01-01T00:00:00）
     * @param endTime   ISO-8601
     */
    @GET
    @Path("/search")
    public Uni<ResponseResult<PageResult<SensitiveQuestionItem>>> search(
            @QueryParam("account") String account,
            @QueryParam("label") String label,
            @RestQuery String beginTime,
            @RestQuery String endTime,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Non-compliant question log search request, account={}, label={}, beginTime={}, endTime={}",
                account, label, beginTime, endTime);
        try {
            LocalDateTime begin = beginTime == null || beginTime.isBlank() ? null : LocalDateTime.parse(beginTime);
            LocalDateTime end = endTime == null || endTime.isBlank() ? null : LocalDateTime.parse(endTime);
            return questionQueryService.search(new SensitiveQuestionQuery(account, label, begin, end, page, pageSize))
                    .map(result -> ResponseResult.ok(result, locale));
        } catch (DateTimeParseException e) {
            logger.error("Invalid time parameter format: beginTime={}, endTime={}", beginTime, endTime);
            return Uni.createFrom().failure(new com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException(
                    com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum.USER_ERROR_A0421,
                    "时间格式不合法，应为 ISO-8601（如 2026-01-01T00:00:00）"));
        }
    }
}
