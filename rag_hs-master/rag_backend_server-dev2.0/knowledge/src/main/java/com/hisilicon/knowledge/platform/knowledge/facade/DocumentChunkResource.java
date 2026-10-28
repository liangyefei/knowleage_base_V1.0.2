package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocChunk;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.knowledge.query.DocumentChunkQueryService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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
 * 文档切片查询 REST 接口：按 documentId 调模型 {@code get_doc_by_filename} 返回切片。
 *
 * <p>配合 {@code /kb/documents/name-options} 下拉选中的 documentId，取文档 kbId+filePath 后调模型取切片
 * （{@code collection_name}=kbId，{@code filename}=/kb-files/+filePath），透传模型 {@code data} 为
 * {@code code=0} 响应。{@code @Path("/kb/documents/chunks")} 与 {@link DocumentResource}（{@code /kb/documents}）
 * 同前缀但独立类，RestEasy 按最具体 @Path 路由（DocumentResource 无 {@code /chunks} 子路径，无冲突）。</p>
 */
@Path("/kb/documents/chunks")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DocumentChunkResource {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkResource.class);

    private final DocumentChunkQueryService documentChunkQueryService;

    @Context
    HttpHeaders httpHeaders;

    public DocumentChunkResource(DocumentChunkQueryService documentChunkQueryService) {
        this.documentChunkQueryService = documentChunkQueryService;
    }

    /**
     * 查询文档切片
     *
     * @param documentId 文档 id（name-options 下拉选中值）
     * @return 切片列表（模型 {@code data} 透传，{@code code=0}）
     */
    @GET
    public Uni<ResponseResult<List<DocChunk>>> chunks(
            @QueryParam("documentId") @NotBlank(message = "{app.document.id.notblank}") String documentId) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        String trimmed = documentId.trim();
        logger.info("Document chunk query request, documentId={}, locale={}", trimmed, locale);
        return documentChunkQueryService.getChunks(trimmed, locale)
                .map(list -> ResponseResult.ok(list, locale));
    }
}
