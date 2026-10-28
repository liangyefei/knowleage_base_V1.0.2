package com.hisilicon.knowledge.platform.commons.ai.client;

import com.hisilicon.knowledge.platform.commons.ai.api.ModelDocApi;
import com.hisilicon.knowledge.platform.commons.ai.dto.GetDocByFilenameRequest;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocChunk;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocumentChunkClient;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 文档切片模型调用实现（{@link DocumentChunkClient} 端口，llm 侧）。
 *
 * <p>调模型 {@code get_doc_by_filename}，模型 {@code code=200} 时提取 {@code data} 返回；非 200 或调用异常
 * → 抛 {@link ModelServiceException}（C0001 + modelServiceError 国际化），经 {@code BusinessExceptionMapper}
 * 统一返回 {@code code=0} 外壳内 {@code code=C0001}。</p>
 *
 * <p>运行期由 service 聚合注入 knowledge 的 DocumentChunkQueryService；knowledge 测试侧无本类（不在
 * classpath），由 MockDocumentChunkClient 满足注入。</p>
 *
 * <p>排查日志：调用前记 url + endpoint + 请求参数（截断）；失败先记原始错误（含 url + 请求，在 transform
 * 转 C0001 前）；成功记切片数 + 响应摘要（截断，防大文档刷屏）；null/非 200/data 缺失记 warn。
 * url 取自 {@code quarkus.rest-client.model-service.url}。实际出站 URI（method + 完整 URL）由
 * {@link RestClientRequestLogger} 记录（经 {@link ModelDocApi} 上的 {@code @RegisterProvider} 注册）。</p>
 */
@ApplicationScoped
public class DocumentChunkClientImpl implements DocumentChunkClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkClientImpl.class);

    private final ModelDocApi api;
    private final String modelServiceUrl;

    public DocumentChunkClientImpl(@RestClient ModelDocApi api,
                                   @ConfigProperty(name = "quarkus.rest-client.model-service.url",
                                           defaultValue = "")
                                   String modelServiceUrl) {
        this.api = api;
        this.modelServiceUrl = modelServiceUrl;
    }

    @Override
    public Uni<List<DocChunk>> getDocByFilename(String collectionName, String fullFilename, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        GetDocByFilenameRequest request = new GetDocByFilenameRequest(collectionName, fullFilename);
        log.info("Calling model service get_doc_by_filename, url={}, endpoint=POST /knowledge_base/get_doc_by_filename, request params: {}",
                modelServiceUrl, LogTruncate.truncate(request));
        return api.getDocByFilename(request)
                // 模型调用失败（连不上/5xx/超时）：先记原始错误（含 URL + 请求参数），再 transform 为 C0001 国际化
                .onFailure().invoke(t -> log.error(
                        "model service get_doc_by_filename call failed, url={}, request: {}, error: {}",
                        modelServiceUrl, LogTruncate.truncate(request), t.toString(), t))
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                // 模型返回但非 200 / data 缺失 → warn 后抛 C0001 国际化
                .onItem().transform(r -> {
                    if (r == null || !r.isSuccess() || r.data() == null) {
                        log.warn("model service get_doc_by_filename abnormal response, url={}, response: {}",
                                modelServiceUrl, LogTruncate.truncate(r));
                        throw new ModelServiceException(appMessages.modelServiceError());
                    }
                    log.info("model service get_doc_by_filename response, url={}, chunk count={}, response summary: {}",
                            modelServiceUrl, r.data().size(), LogTruncate.truncate(r));
                    return r.data();
                });
    }
}