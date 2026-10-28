package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.llm.sensitive.command.AddSensitiveWordCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.ChangeSensitiveWordStatusCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.DeleteSensitiveWordCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.EditSensitiveWordCommand;
import com.hisilicon.knowledge.platform.llm.sensitive.command.SensitiveWordCommandService;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordExcel;
import com.hisilicon.knowledge.platform.llm.sensitive.query.SensitiveWordQueryService;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveWordItem;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 敏感词词库 REST 接口（系统管理-敏感词管理）
 * <p>模板/导入/导出均为 xlsx（二进制，规避 CSV 下载链路编码乱码）；导入兼容 5 列 csv（UTF-8）。
 * 导入导出同构：标签名称|标签标识|标签是否启用|敏感词|词是否启用，导出文件可直接回导。</p>
 */
@Path("/system/sensitive/words")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SensitiveWordResource {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveWordResource.class);

    /** xlsx MIME 类型 */
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SensitiveWordCommandService wordCommandService;
    private final SensitiveWordQueryService wordQueryService;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    @Inject
    public SensitiveWordResource(SensitiveWordCommandService wordCommandService,
                                 SensitiveWordQueryService wordQueryService) {
        this.wordCommandService = wordCommandService;
        this.wordQueryService = wordQueryService;
    }

    /**
     * 词库分页查询（tagId 可选：不传查全部；可按词模糊、状态过滤）
     */
    @GET
    @Path("/search")
    public Uni<ResponseResult<PageResult<SensitiveWordItem>>> search(
            @QueryParam("tagId") String tagId,
            @QueryParam("word") String word,
            @QueryParam("status") Short status,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Sensitive word search request, tagId={}, word={}, status={}, page={}, pageSize={}",
                tagId, word, status, page, pageSize);
        return wordQueryService.search(tagId, word, status, page, pageSize)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 新增敏感词
     */
    @POST
    @Path("/add")
    public Uni<ResponseResult<Void>> add(@Valid AddSensitiveWordCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Add sensitive word request, tagId={}, word={}, operatorId={}", command.tagId(), command.word(), operatorId);
        return wordCommandService.add(command, operatorId, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 编辑敏感词（全量替换：分类 + 词文本；状态切换走 /changeStatus）
     */
    @POST
    @Path("/edit")
    public Uni<ResponseResult<Void>> edit(@Valid EditSensitiveWordCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Edit sensitive word request, id={}, tagId={}, word={}", command.id(), command.tagId(), command.word());
        return wordCommandService.edit(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除敏感词
     */
    @POST
    @Path("/delete")
    public Uni<ResponseResult<Void>> delete(@Valid DeleteSensitiveWordCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Delete sensitive word request, count={}", command.ids().size());
        return wordCommandService.delete(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 批量切换词状态（1=启用 / 2=停用）；词停用后不参与敏感词拦截
     */
    @POST
    @Path("/changeStatus")
    public Uni<ResponseResult<Void>> changeStatus(@Valid ChangeSensitiveWordStatusCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Change sensitive word status request, count={}, status={}", command.ids().size(), command.status());
        return wordCommandService.changeStatus(command, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 导入词库（multipart：file；支持 .xlsx 与 5 列 .csv（UTF-8），一次可导入多个分类并设置标签/词启用状态）
     */
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<SensitiveWordCommandService.ImportResult>> importWords(
            @RestForm("file") FileUpload file) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String operatorId = operatorIdHolder.get();
        logger.info("Word library import request, fileName={}, operatorId={}",
                file != null ? file.fileName() : null, operatorId);
        if (file == null) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME, appMessages.sensitiveUploadFileBlank()));
        }
        byte[] content;
        try {
            content = Files.readAllBytes(file.uploadedFile());
        } catch (IOException e) {
            logger.error("Failed to read uploaded word library file", e);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.ERROR_READ_FILE, appMessages.sensitiveFileReadFailed()));
        }
        return wordCommandService.importWords(file.fileName(), content, operatorId, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 导出词库 xlsx（跨标签：tagId 可选，可按词过滤；与导入同构，导出文件可直接回导）
     * <p>无命中数据时不产出空文档，改走统一业务信封（A0430 + 提示文案），
     * 由前端按 JSON 响应提示"无数据可导出"。</p>
     */
    @GET
    @Path("/export")
    @Produces(XLSX_MIME)
    public Uni<Response> export(@QueryParam("tagId") String tagId, @QueryParam("word") String word) {
        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE));
        logger.info("Word library export request, tagId={}, word={}", tagId, word);
        return wordQueryService.listForExport(tagId, word)
                .onItem().transform(rows -> {
                    if (rows.isEmpty()) {
                        logger.info("Word library export skipped (no matched data), tagId={}, word={}", tagId, word);
                        throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0430,
                                appMessages.sensitiveExportNoData());
                    }
                    byte[] xlsx;
                    try {
                        xlsx = SensitiveWordExcel.writeExport(rows);
                    } catch (IOException e) {
                        logger.error("Failed to generate word library export xlsx", e);
                        throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0004, appMessages.sensitiveExportFailed());
                    }
                    String fileName = URLEncoder.encode("敏感词词库.xlsx", StandardCharsets.UTF_8);
                    return Response.ok(xlsx)
                            .header("Content-Disposition", "attachment;filename=" + fileName)
                            .build();
                });
    }

    /**
     * 下载词库导入模板 xlsx（运行时生成：Sheet1 五列表头 + Sheet2 填写说明）
     */
    @GET
    @Path("/template")
    @Produces(XLSX_MIME)
    public Response downloadTemplate() {
        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE));
        byte[] xlsx;
        try {
            xlsx = SensitiveWordExcel.buildTemplate();
        } catch (IOException e) {
            logger.error("Failed to generate word library import template", e);
            throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0506, appMessages.sensitiveTemplateBuildFailed());
        }
        String fileName = URLEncoder.encode("敏感词导入模板.xlsx", StandardCharsets.UTF_8);
        return Response.ok(xlsx)
                .header("Content-Disposition", "attachment;filename=" + fileName)
                .build();
    }
}
