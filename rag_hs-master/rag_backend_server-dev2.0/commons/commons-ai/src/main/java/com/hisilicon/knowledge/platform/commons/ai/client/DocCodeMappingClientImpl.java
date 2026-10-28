package com.hisilicon.knowledge.platform.commons.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.hisilicon.knowledge.platform.commons.ai.api.KgServiceApi;
import com.hisilicon.knowledge.platform.commons.ai.dto.DocCodeRequest;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingClient;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingQuery;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingResult;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 代码↔文档映射模型调用实现（{@link DocCodeMappingClient} 端口，llm 侧）。
 *
 * <p>调 dual-mcp {@code POST /doc_code}（{@link KgServiceApi#docCode}），HTTP 200 时拿到原始响应体
 * 并容错解析为端口结果 {@link DocCodeMappingResult} 透传。不与响应 DTO 强类型绑定：测试/生产
 * dual-mcp 版本 schema 存在漂移（{@code count}/{@code elapsed_ms} 曾出现非数值类型），非数值数值字段
 * 记 warn 置 null，由 count 空结果兜底语义降级；{@code matched} 自 v1 文档起即为<b>字符串</b>判向结果
 * （{@code document}/{@code code_file}），按文本解析，不再当数值处理（旧实现按 int 解析导致恒为 null）。
 * 解析失败（非法 JSON/空体）时 error 日志带原始 body 后抛 {@link ModelServiceException}（C0001）。
 * 模型 {@code WebApplicationException}（404 项目图谱不存在 / 400 参数非法等）<b>原样抛出</b>，
 * 由 knowledge 门面按 HTTP 状态码转换为业务错误码（A0430/A0420）；其余调用异常包装为
 * {@link ModelServiceException}（C0001 + modelServiceError 国际化）。null 响应记 warn 后抛 C0001。</p>
 */
@ApplicationScoped
public class DocCodeMappingClientImpl implements DocCodeMappingClient {

    private static final Logger log = LoggerFactory.getLogger(DocCodeMappingClientImpl.class);

    /** 响应体日志上限（字符），超出截断，避免大 results 撑爆日志 */
    private static final int BODY_LOG_LIMIT = 2000;

    /**
     * /doc_code 顶层字段（API-DOCCODE2.md §4）；results 内部结构随方向变化，不做逐项约束。
     * 包私有而非 private：ModelContractTest 用同一套声明断言真实服务响应。
     */
    static final Set<String> DOC_CODE_FIELDS = Set.of(
            "file", "project", "direction", "matched", "count", "results", "hint", "elapsed_ms");
    static final Set<String> DOC_CODE_REQUIRED = Set.of("count", "results");

    private final KgServiceApi api;
    private final String kgServiceUrl;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public DocCodeMappingClientImpl(@RestClient KgServiceApi api,
                                    @ConfigProperty(name = "quarkus.rest-client.kg-service.url", defaultValue = "")
                                    String kgServiceUrl,
                                    com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.api = api;
        this.kgServiceUrl = kgServiceUrl;
        this.mapper = mapper;
    }

    @Override
    public Uni<DocCodeMappingResult> docCode(DocCodeMappingQuery query) {
        AppMessages appMessages = LocaleUtil.getAppMessage(null);
        DocCodeRequest request = new DocCodeRequest(
                query.file(), query.project(), query.direction(),
                query.limit(), toInt(query.withSource()), toInt(query.withDoc()),
                toInt(query.exact() != null ? query.exact() : Boolean.FALSE));
        // 出站日志打印实际发送的 request.file()（经 knowledge 侧剥离存储前缀后的图谱相对路径），
        // 排查 422 时以本日志为准（而非上层透传值）
        log.info("Calling dual-mcp doc-code mapping, url={}, endpoint=POST /doc_code, outbound request: file={}, project={}, direction={}, limit={}, with_source={}, with_doc={}, exact={}",
                kgServiceUrl, request.file(), request.project(), request.direction(),
                request.limit(), request.with_source(), request.with_doc(), request.exact());
        return api.docCode(request)
                // 失败：先记原始错误（含 URL + 请求参数）；WebApplicationException 读取并缓冲
                // 错误响应体（否则 REST Client 关闭响应后 detail 丢失），原样抛出由上层按状态码转换
                .onFailure().invoke(t -> {
                    if (t instanceof WebApplicationException wae) {
                        String body = readAndBufferBody(wae);
                        log.error("dual-mcp /doc_code call failed, url={}, request: file={}, project={}, "
                                        + "status={}, response body: {}", kgServiceUrl, query.file(), query.project(),
                                wae.getResponse() != null ? wae.getResponse().getStatus() : 0, body, t);
                    } else {
                        log.error("dual-mcp /doc_code call failed, url={}, request: file={}, project={}, error: {}",
                                kgServiceUrl, query.file(), query.project(), t.toString(), t);
                    }
                })
                .onFailure().transform(t -> t instanceof WebApplicationException
                        ? t
                        : new ModelServiceException(appMessages.modelServiceError(), t))
                // 成功：拿到原始响应体，记日志后容错解析；null 响应记 warn 后抛 C0001
                .onItem().transform(body -> {
                    if (body == null || body.isBlank()) {
                        log.warn("dual-mcp /doc_code response is null or empty, url={}, request: file={}, project={}",
                                kgServiceUrl, query.file(), query.project());
                        throw new ModelServiceException(appMessages.modelServiceError());
                    }
                    log.info("dual-mcp /doc_code response, url={}, request: file={}, project={}, direction={}, body: {}",
                            kgServiceUrl, query.file(), query.project(), query.direction(), truncate(body));
                    return parse(body, query, appMessages);
                });
    }

    /**
     * 容错解析原始响应体 → 端口结果：文本字段（file/project/direction/matched/hint）取 text；
     * {@code matched} 是字符串判向结果（document|code_file|null，文档 §4 口径）；
     * 数值字段（count/elapsed_ms）仅在 JSON 数值时取值（类型漂移记 warn 置 null），
     * results 原样透传 JsonNode。解析失败抛 {@link ModelServiceException}（C0001）。
     * 所有日志携带请求参数（file/project/direction），便于按 traceId 对齐请求上下文。
     */
    private DocCodeMappingResult parse(String body, DocCodeMappingQuery query, AppMessages appMessages) {
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.error("dual-mcp /doc_code response is not valid JSON, url={}, request: file={}, project={}, direction={}, body: {}",
                    kgServiceUrl, query.file(), query.project(), query.direction(), truncate(body));
            throw new ModelServiceException(appMessages.modelServiceError(), e);
        }
        if (node == null || !node.isObject()) {
            log.warn("dual-mcp /doc_code response is not a JSON object, url={}, request: file={}, project={}, direction={}, body: {}",
                    kgServiceUrl, query.file(), query.project(), query.direction(), truncate(body));
            throw new ModelServiceException(appMessages.modelServiceError());
        }
        ModelResponseDrift.check("POST /doc_code", node, DOC_CODE_FIELDS, DOC_CODE_REQUIRED);
        String matched = textOrNull(node, "matched");
        Integer count = intOrNull(node, "count");
        Long elapsedMs = longOrNull(node, "elapsed_ms");
        log.info("dual-mcp /doc_code parsed, url={}, request: file={}, project={}, direction={}: matched={}, count={}, hint={}",
                kgServiceUrl, query.file(), query.project(), query.direction(), matched, count, textOrNull(node, "hint"));
        return new DocCodeMappingResult(
                textOrNull(node, "file"),
                textOrNull(node, "project"),
                textOrNull(node, "direction"),
                matched,
                count,
                node.get("results"),
                textOrNull(node, "hint"),
                elapsedMs);
    }

    /** 字段存在且为数值时取 int，否则记 warn 返回 null（schema 漂移容错） */
    private Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.intValue();
        }
        log.warn("dual-mcp /doc_code field '{}' is not a number (value: {}), treating as null, url={}",
                field, v.toString(), kgServiceUrl);
        return null;
    }

    /** 字段存在且为数值时取 long，否则记 warn 返回 null（schema 漂移容错） */
    private Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.longValue();
        }
        log.warn("dual-mcp /doc_code field '{}' is not a number (value: {}), treating as null, url={}",
                field, v.toString(), kgServiceUrl);
        return null;
    }

    /** 字段存在且为文本时取值，否则返回 null */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String truncate(String body) {
        return body != null && body.length() > BODY_LOG_LIMIT ? body.substring(0, BODY_LOG_LIMIT) + "…" : body;
    }

    /** 端口布尔标志 → 模型 int 语义（0=不附，1=附带；null 视为不附） */
    private static Integer toInt(Boolean flag) {
        return Boolean.TRUE.equals(flag) ? 1 : 0;
    }

    /**
     * 读取并缓冲非 2xx 错误响应体（bufferEntity 后可由上层重复 readEntity）。
     * 读取失败仅记日志，不影响原异常抛出。
     */
    private static String readAndBufferBody(WebApplicationException wae) {
        try {
            if (wae.getResponse() == null) {
                return null;
            }
            wae.getResponse().bufferEntity();
            String entity = wae.getResponse().readEntity(String.class);
            return truncate(entity);
        } catch (ProcessingException | IllegalStateException e) {
            log.warn("Failed to read dual-mcp /doc_code error response body: {}", e.toString());
            return null;
        }
    }
}
