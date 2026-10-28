package com.hisilicon.knowledge.platform.commons.ai.api;

import com.hisilicon.knowledge.platform.commons.ai.client.ModelServiceRestClient;
import com.hisilicon.knowledge.platform.commons.ai.client.RestClientRequestLogger;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelApiResponse;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelChatRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelFileChatRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelKnowledgeBaseChatRequest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm;

/**
 * 模型服务 REST Client API（HaiSI RAG Server）
 * <p>接口文档：models_api.html。SSE 流式对话 + multipart 临时文件上传。</p>
 *
 * <p>{@link RegisterProvider} 注册 {@link RestClientRequestLogger} 记录实际出站 URI
 * （method + 完整 URL），便于排查"调用模型的地址"；请求参数与响应结果由
 * {@link ModelServiceRestClient} 侧日志记录（filter 对 multipart/SSE 取实体不可靠）。</p>
 */
@Path("/kw")
@RegisterRestClient(configKey = "model-service")
@RegisterProvider(RestClientRequestLogger.class)
public interface ModelServiceApi {

    /**
     * 上传文件到临时目录并向量化，返回临时知识库 ID
     *
     * @param form multipart 表单（ClientMultipartForm.create() 构建，files 字段为二进制）
     */
    @POST
    @Path("/knowledge_base/upload_temp_docs")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<ModelApiResponse> uploadTempDocs(ClientMultipartForm form);

    /**
     * 与临时上传文件对话（SSE 流式）
     */
    @POST
    @Path("/chat/file_chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    Multi<String> fileChat(ModelFileChatRequest request);

    /**
     * 与知识库对话（multi-KB RAG，SSE 流式）
     */
    @POST
    @Path("/chat/multi_knowledge_base_chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    Multi<String> multiKnowledgeBaseChat(ModelKnowledgeBaseChatRequest request);

    /**
     * 纯 LLM 对话（SSE 流式）
     */
    @POST
    @Path("/chat/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    Multi<String> chat(ModelChatRequest request);
}