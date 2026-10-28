package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.CreateImportBatchCommand;
import com.hisilicon.knowledge.platform.knowledge.command.ImportBatchCommandService;
import com.hisilicon.knowledge.platform.knowledge.query.ImportBatchQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ImportBatchProgress;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 导入批次管理（{@code /kb/import/batches}）— 初始化灌库的批次聚合入口。
 * <p>POST 创建批次（kbId/categoryId/batchName/defaultMetadata/draftConflictPolicy）；
 * GET 进度（upload_sessions 聚合）/列表；POST abort 中止。</p>
 */
@Authenticated
@Path("/kb/import/batches")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ImportBatchResource {

    private static final Logger logger = LoggerFactory.getLogger(ImportBatchResource.class);

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    @Inject
    ImportBatchCommandService batchCommandService;

    @Inject
    ImportBatchQueryService batchQueryService;

    /** 创建批次，返回 batchId（前端持之逐文件 tus create 时在 Upload-Metadata 携带） */
    @POST
    public Uni<ResponseResult<String>> createBatch(CreateImportBatchCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Create import batch: kbId={}, batchName={}, operatorId={}",
                command != null ? command.kbId() : null,
                command != null ? command.batchName() : null, operatorId);
        return batchCommandService.createBatch(operatorId, command, locale)
                .map(batchId -> ResponseResult.ok(batchId, locale));
    }

    /** 批次进度（upload_sessions 聚合：total/success/failed/uploading/created 清单） */
    @GET
    @Path("/{id}")
    public Uni<ResponseResult<ImportBatchProgress>> getProgress(@PathParam("id") String id) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return batchQueryService.getProgress(id)
                .map(progress -> ResponseResult.ok(progress, locale));
    }

    /** 当前用户批次列表（按创建时间倒序） */
    @GET
    public Uni<ResponseResult<List<ImportBatchProgress>>> listBatches() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        return batchQueryService.listBatches(operatorId)
                .map(list -> ResponseResult.ok(list, locale));
    }

    /** 中止批次：未完成会话置 ABORTED + 临时文件移 trash，批次 ABORTED */
    @POST
    @Path("/{id}/abort")
    public Uni<ResponseResult<Void>> abort(@PathParam("id") String id) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String operatorId = operatorIdHolder.get();
        logger.info("Abort import batch: batchId={}, operatorId={}", id, operatorId);
        return batchCommandService.abort(operatorId, id, locale)
                .map(v -> ResponseResult.ok(null, locale));
    }
}
