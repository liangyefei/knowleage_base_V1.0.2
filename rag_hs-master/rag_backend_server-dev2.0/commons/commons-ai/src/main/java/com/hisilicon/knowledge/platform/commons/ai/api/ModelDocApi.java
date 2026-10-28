package com.hisilicon.knowledge.platform.commons.ai.api;

import com.hisilicon.knowledge.platform.commons.ai.client.DocumentChunkClientImpl;
import com.hisilicon.knowledge.platform.commons.ai.client.RestClientRequestLogger;
import com.hisilicon.knowledge.platform.commons.ai.dto.DocByFilenameResponse;
import com.hisilicon.knowledge.platform.commons.ai.dto.GetDocByFilenameRequest;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * 模型文档查询 REST Client（HaiSI RAG Server {@code get_doc_by_filename}）。
 *
 * <p>与 {@link ModelServiceApi}（{@code @Path("/kw")} 下的 chat/upload）不同，本接口路径无 {@code /kw}
 * 前缀（按接口文档/实测 curl：{@code /knowledge_base/get_doc_by_filename}），故独立接口、无类级 {@code @Path}，
 * 方法 {@code @Path} 直接拼到 model-service 基址。复用 {@code configKey="model-service"} 同一基址与超时配置
 * （{@code quarkus.rest-client.model-service.url}）。</p>
 *
 * <p>{@link RegisterProvider} 注册 {@link RestClientRequestLogger} 记录实际出站 URI（method + 完整 URL）；
 * 请求参数与响应结果由 {@link DocumentChunkClientImpl} 侧日志记录。</p>
 */
@RegisterRestClient(configKey = "model-service")
@RegisterProvider(RestClientRequestLogger.class)
public interface ModelDocApi {

    /**
     * 按知识库 + 文件名查询文档切片（已向量化内容）。
     *
     * @param request {@code collection_name} + {@code filename}
     * @return 模型响应信封（{@code code/msg/data}）
     */
    @POST
    @Path("/knowledge_base/get_doc_by_filename")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<DocByFilenameResponse> getDocByFilename(GetDocByFilenameRequest request);
}