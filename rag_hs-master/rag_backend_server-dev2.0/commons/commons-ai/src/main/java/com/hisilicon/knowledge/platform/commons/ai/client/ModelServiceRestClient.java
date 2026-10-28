package com.hisilicon.knowledge.platform.commons.ai.client;

import com.hisilicon.knowledge.platform.commons.ai.api.ModelServiceApi;
import com.hisilicon.knowledge.platform.commons.ai.api.ModelServiceClient;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelApiResponse;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelChatRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelFileChatRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelHistoryItem;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelKnowledgeBaseChatRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.UploadTempDocsForm;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 模型服务 REST Client 实现
 * <p>对接 HaiSI RAG Server。SSE 事件为模型服务 data 内容原样透传，
 * 事件格式待模型服务实测后对齐（见架构文档 §3.14.3）。</p>
 * <p>统一原则：能不传就不传——model_name/temperature/max_tokens/prompt_name 等可选参数
 * 一律不下发（null → JSON 省略），由模型服务按自身 configs/model_config.yaml 默认值处理，
 * 避免后端覆盖默认值。唯 stream=true 为 SSE 必需保留。</p>
 *
 * <p>排查日志：调用前记 url + endpoint + 请求参数（截断）；SSE 流每事件 DEBUG（截断）+ 结束汇总 INFO
 * （拼接结果截断）；失败记原始错误（含 url + 请求参数，在异常 transform 前）。url 取自
 * {@code quarkus.rest-client.model-service.url}（已表达式解析，含环境变量覆盖）。实际出站 URI
 * （method + 完整 URL）由 {@link RestClientRequestLogger} 记录（经 {@link ModelServiceApi} 上的
 * {@code @RegisterProvider} 注册）。</p>
 */
@ApplicationScoped
public class ModelServiceRestClient implements ModelServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(ModelServiceRestClient.class);

    /** SSE 每事件日志截断上限（字符），DEBUG 级别。 */
    private static final int SSE_EVENT_LOG_MAX = 500;
    /** SSE 结束汇总日志中拼接结果截断上限（字符），INFO 级别。 */
    private static final int SSE_SUMMARY_LOG_MAX = 1000;

    /**
     * 规则2：是否向模型下发搜索范围+芯片型号+文档类型+版本（search_scope/chip_models/doc_types/version）。
     * <p>模型协议尚未定义这些字段，默认 false（置 null → JSON 省略，避免 422 未知字段）；
     * 模型确认后置 true 激活下发，无需改代码。注意：{@code deep_think} 已由模型支持（见
     * {@code docs/智能问答接口.md}），不再受此开关控制，直接下发。</p>
     */
    private final boolean sendFilters;

    private final ModelServiceApi api;
    private final String modelServiceUrl;

    public ModelServiceRestClient(@RestClient ModelServiceApi api,
                                  @ConfigProperty(name = "kb.model.knowledge-base-chat.send-filters",
                                          defaultValue = "false")
                                  boolean sendFilters,
                                  @ConfigProperty(name = "quarkus.rest-client.model-service.url",
                                          defaultValue = "")
                                  String modelServiceUrl) {
        this.api = api;
        this.sendFilters = sendFilters;
        this.modelServiceUrl = modelServiceUrl;
    }

    @Override
    public Uni<String> uploadTempDocs(String fileName, byte[] content) {
        logger.info("Calling model service upload_temp_docs, url={}, endpoint=POST /kw/knowledge_base/upload_temp_docs, "
                        + "request params: fileName={}, size={} bytes",
                modelServiceUrl, fileName, content.length);
        return api.uploadTempDocs(UploadTempDocsForm.of(fileName, content))
                .onFailure().invoke(t -> logger.error(
                        "model service upload_temp_docs call failed, url={}, request: fileName={}, error: {}",
                        modelServiceUrl, fileName, t.toString(), t))
                .onItem().transform(this::extractTempKbId);
    }

    @Override
    public Multi<String> fileChat(String query, String knowledgeId, boolean deepThinking,
                                 List<ModelHistoryItem> history, String account) {
        // deep_think 模型已支持（见 docs/智能问答接口.md），直接下发（null 省略=模型默认 false；true 时下发）。
        // model_name/temperature/max_tokens/prompt_name 一律不下发（null → JSON 省略），
        // 由模型服务按自身 configs/model_config.yaml 默认值处理；stream=true 为 SSE 必需保留。
        Boolean deepThinkingPayload = deepThinking;
        ModelFileChatRequest request = new ModelFileChatRequest(
                query, knowledgeId, account, history, true, null, null, null, null,
                deepThinkingPayload);
        logger.info("Calling model service file_chat, url={}, endpoint=POST /kw/chat/file_chat, request params: {}",
                modelServiceUrl, LogTruncate.truncate(request));
        StringBuilder buf = new StringBuilder();
        return api.fileChat(request)
                .onItem().invoke(chunk -> {
                    buf.append(chunk);
                    logger.debug("file_chat SSE event: {}", LogTruncate.truncate(chunk, SSE_EVENT_LOG_MAX));
                })
                .onCompletion().invoke(() -> logger.info(
                        "file_chat response finished, url={}, total {} chars, joined result: {}",
                        modelServiceUrl, buf.length(), LogTruncate.truncate(buf.toString(), SSE_SUMMARY_LOG_MAX)))
                .onFailure().invoke(t -> logger.error(
                        "model service file_chat call failed, url={}, request params: {}, error: {}",
                        modelServiceUrl, LogTruncate.truncate(request), t.toString(), t));
    }

    @Override
    public Multi<String> multiKnowledgeBaseChat(ModelKnowledgeBaseChatRequest request) {
        // deep_think 模型已支持（见 docs/智能问答接口.md），直接下发（null 省略=模型默认 false；true 时下发）。
        // search_scope/doc_types/version 规则2 预留：模型协议未定，默认不下发（null → JSON 省略，规避 422），
        // 由 kb.model.knowledge-base-chat.send-filters=true 激活下发（模型协议确认后开启）。
        // chip_models 现恒 null（kbIds 已作为 knowledge_base_names 下发，省略）。
        // enable_internet_search 联网搜索三态（null=自动/true=强制/false=禁用）：模型已支持，原样透传，
        // 不受 send-filters 门控（同 deep_think；仅本端点支持）。
        // model_name/temperature/top_k/score_threshold/max_tokens/prompt_name 一律不下发（null → JSON 省略），
        // 由模型服务按自身 configs/model_config.yaml 默认值处理，避免后端覆盖默认值；stream=true 为 SSE 必需保留。
        ModelKnowledgeBaseChatRequest payload = new ModelKnowledgeBaseChatRequest(
                request.query(), request.knowledgeBaseNames(), request.userId(), request.topK(),
                request.scoreThreshold(), request.history(), true,
                request.modelName(), request.temperature(), request.maxTokens(), request.promptName(),
                sendFilters ? request.searchScope() : null, null,
                request.deepThinking(),
                sendFilters ? request.docTypes() : null,
                sendFilters ? request.version() : null,
                request.enableInternetSearch());
        logger.info("Calling model service multi_knowledge_base_chat, url={}, endpoint=POST /kw/chat/multi_knowledge_base_chat, request params: {}",
                modelServiceUrl, LogTruncate.truncate(payload));
        StringBuilder buf = new StringBuilder();
        return api.multiKnowledgeBaseChat(payload)
                .onItem().invoke(chunk -> {
                    buf.append(chunk);
                    logger.debug("multi_knowledge_base_chat SSE event: {}", LogTruncate.truncate(chunk, SSE_EVENT_LOG_MAX));
                })
                .onCompletion().invoke(() -> logger.info(
                        "multi_knowledge_base_chat response finished, url={}, total {} chars, joined result: {}",
                        modelServiceUrl, buf.length(), LogTruncate.truncate(buf.toString(), SSE_SUMMARY_LOG_MAX)))
                .onFailure().invoke(t -> logger.error(
                        "model service multi_knowledge_base_chat call failed, url={}, request params: {}, error: {}",
                        modelServiceUrl, LogTruncate.truncate(payload), t.toString(), t));
    }

    @Override
    public Multi<String> chat(String query, boolean deepThinking, List<ModelHistoryItem> history, String account) {
        // deep_think 模型已支持（见 docs/智能问答接口.md），直接下发（null 省略=模型默认 false；true 时下发）。
        // model_name/temperature/conversation_id/history_len/max_tokens/prompt_name 一律不下发（null → JSON 省略），
        // 由模型服务按自身 configs/model_config.yaml 默认值处理；stream=true 为 SSE 必需保留。
        Boolean deepThinkingPayload = deepThinking;
        ModelChatRequest request = new ModelChatRequest(
                query, null, account, null, history, true, null, null, null, null,
                deepThinkingPayload);
        logger.info("Calling model service chat, url={}, endpoint=POST /kw/chat/chat, request params: {}",
                modelServiceUrl, LogTruncate.truncate(request));
        StringBuilder buf = new StringBuilder();
        return api.chat(request)
                .onItem().invoke(chunk -> {
                    buf.append(chunk);
                    logger.debug("chat SSE event: {}", LogTruncate.truncate(chunk, SSE_EVENT_LOG_MAX));
                })
                .onCompletion().invoke(() -> logger.info(
                        "chat response finished, url={}, total {} chars, joined result: {}",
                        modelServiceUrl, buf.length(), LogTruncate.truncate(buf.toString(), SSE_SUMMARY_LOG_MAX)))
                .onFailure().invoke(t -> logger.error(
                        "model service chat call failed, url={}, request params: {}, error: {}",
                        modelServiceUrl, LogTruncate.truncate(request), t.toString(), t));
    }

    /**
     * 从 BaseResponse 提取临时知识库 ID
     */
    private String extractTempKbId(ModelApiResponse response) {
        if (response == null || !response.isSuccess() || response.data() == null) {
            logger.error("model service upload_temp_docs abnormal response, url={}, response: {}",
                    modelServiceUrl, LogTruncate.truncate(response));
            throw new com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException(
                    "Model service temporary file upload failed");
        }
        String tempKbId = response.data().toString();
        logger.info("model service upload_temp_docs upload succeeded, url={}, tempKbId={}", modelServiceUrl, tempKbId);
        return tempKbId;
    }
}