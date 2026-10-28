package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.CreateUploadSessionCommand;
import com.hisilicon.knowledge.platform.knowledge.command.EditDocumentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.UploadDocumentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.UploadSessionCommandService;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionPurpose;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.exception.TusProtocolException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * tus 协议端点（{@code /kb/documents/tus}，tus Protocol 1.0.0）
 * <p>对前端暴露标准 tus 端点，可对接 {@code tus-js-client}。响应遵循 tus 头规范（不复用 ResponseResult）；
 * 协议层错误经 {@link TusProtocolExceptionMapper} 出真实状态码 + 统一 envelope + 具体原因
 * （409/410/404/412，不再被通用映射器压成 A0421）；业务异常经 {@code BusinessExceptionMapper}
 * 出 HTTP 200+JSON envelope（§4.6 complete 失败语义）。</p>
 * <p>{@code @Authenticated} 类级绑定：所有 tus 请求需带 Bearer；CORS 预检 OPTIONS 由 Quarkus 内置 CORS
 * 在到达过滤器前响应（见 §5 待验证项 2）。</p>
 */
@Authenticated
@Path("/kb/documents/tus")
@Produces(MediaType.APPLICATION_JSON)
public class TusUploadResource {

    private static final Logger logger = LoggerFactory.getLogger(TusUploadResource.class);

    private static final String TUS_VERSION = "1.0.0";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    /** JAX-RS 无标准 @PATCH，以 @HttpMethod("PATCH") 自定义（JAX-RS 通用做法） */
    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("PATCH")
    public @interface PATCH {
    }

    @Context
    HttpHeaders httpHeaders;

    @Context
    UriInfo uriInfo;

    @Inject
    OperatorIdHolder operatorIdHolder;

    @Inject
    UploadSessionCommandService sessionCommandService;

    @Inject
    FileStorageService fileStorageService;

    /** tus metadata blob（base64 JSON）反序列化为 UploadDocumentCommand（与普通上传同一 metadata 形态） */
    @Inject
    ObjectMapper objectMapper;

    // ========== OPTIONS（协议握手） ==========

    @OPTIONS
    public Response options() {
        return buildOptionsResponse();
    }

    /**
     * 子路径 OPTIONS：tus 预检与主请求同 URL（{@code OPTIONS /tus/{id}}），而本资源只定义了
     * 根路径 OPTIONS；当网关/客户端直接对分片 URL 发预检（或 Quarkus 内置 CORS 因请求缺
     * Access-Control-Request-Method 头未接管时），请求会落到 RESTEasy 路由并 404（日志中的
     * "Unable to find matching target resource method"）。补齐子路径 OPTIONS，响应头与根路径一致。
     */
    @OPTIONS
    @Path("/{id}")
    public Response optionsWithId() {
        return buildOptionsResponse();
    }

    private Response buildOptionsResponse() {
        return Response.ok()
                .header("Tus-Resumable", TUS_VERSION)
                .header("Tus-Version", TUS_VERSION)
                .header("Tus-Extension", "creation,termination")
                .header("Access-Control-Allow-Methods", "POST,HEAD,PATCH,DELETE,OPTIONS")
                .header("Access-Control-Allow-Headers",
                        "Tus-Resumable,Upload-Length,Upload-Offset,Upload-Metadata,Content-Type,Authorization")
                .header("Access-Control-Expose-Headers",
                        "Tus-Resumable,Upload-Offset,Upload-Length,Upload-Expires,Location")
                .build();
    }

    // ========== POST（创建上传会话） ==========

    @POST
    public Uni<Response> create(@HeaderParam("Tus-Resumable") String tusResumable,
                                @HeaderParam("Upload-Length") long uploadLength,
                                @HeaderParam("Upload-Metadata") String uploadMetadata) {
        requireTusResumable(tusResumable);
        // 契约（见 docs/断点续传.md 与《初始化批量上传设计》§文档编辑/版本覆盖复用 tus）：
        //   filename <b64>（必填）
        //   purpose <b64,可选>：batchImport（缺省）/ docEdit / versionOverride
        //   batchId <b64>：BATCH_IMPORT 必填；targetDocumentId <b64>：doc 用途必填（互斥）
        //   metadata <b64(原 metadata JSON)>：batchImport=UploadDocumentCommand 形态（省略=继承批次 default）；
        //     docEdit=EditDocumentCommand 形态（null 语义同 multipart edit）；versionOverride=UploadDocumentCommand
        //     形态（version 必填）。doc 用途 JSON 非法 → 422 A0400（不静默吞——无批次默认兜底）
        //   draftPolicy <b64,可选 OVERRIDE/SKIP>（仅 batchImport 消费）
        Map<String, String> meta = parseUploadMetadata(uploadMetadata);
        String fileName = meta.get("filename");
        if (fileName == null) {
            return Uni.createFrom().item(Response.status(422).build());
        }
        UploadSessionPurpose purpose;
        try {
            purpose = UploadSessionPurpose.fromString(meta.get("purpose"));
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(422).build());
        }
        boolean batchPurpose = purpose == UploadSessionPurpose.BATCH_IMPORT;
        String batchId = meta.get("batchId");
        String targetDocumentId = meta.get("targetDocumentId");
        if (batchPurpose ? batchId == null : targetDocumentId == null) {
            return Uni.createFrom().item(Response.status(422).build());
        }
        ImportFileMetadata perFileMetadata = parseMetadataJson(meta.get("metadata"), purpose).orElse(null);
        CreateUploadSessionCommand command = new CreateUploadSessionCommand(
                batchId, meta.get("purpose"), targetDocumentId, fileName, meta.get("mimeType"),
                uploadLength, perFileMetadata, meta.get("draftPolicy"));
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return sessionCommandService.create(operatorIdHolder.get(), command, locale)
                .map(session -> Response.created(
                                // 用绝对 URL（含 host + root-path）作 Location，避免相对路径在
                                // root-path=/hisilicon-knowledge 部署下被前端解析丢前缀（→404）
                                uriInfo.getAbsolutePathBuilder().path(session.getId().value()).build())
                        .header("Tus-Resumable", TUS_VERSION)
                        .header("Upload-Expires", httpDate(session.getExpiresAt()).orElse(null))
                        .header("Upload-Offset", 0L)
                        .build());
    }

    // ========== HEAD（查询断点） ==========

    @HEAD
    @Path("/{id}")
    public Uni<Response> head(@HeaderParam("Tus-Resumable") String tusResumable,
                             @PathParam("id") String id) {
        requireTusResumable(tusResumable);
        return sessionCommandService.head(operatorIdHolder.get(), id,
                        httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE))
                .flatMap(session -> fileStorageService.size(session.getTempPath())
                        .map(size -> Response.ok()
                                .header("Tus-Resumable", TUS_VERSION)
                                .header("Upload-Offset", size)
                                .header("Upload-Length", session.getUploadLength())
                                .header("Cache-Control", "no-store")
                                .header("Upload-Expires", httpDate(session.getExpiresAt()).orElse(null))
                                .build()));
    }

    // ========== PATCH（上传分片） ==========

    @PATCH
    @Path("/{id}")
    @jakarta.ws.rs.Consumes("application/offset+octet-stream")
    public Uni<Response> patch(@HeaderParam("Tus-Resumable") String tusResumable,
                               @HeaderParam("Upload-Offset") long uploadOffset,
                               @PathParam("id") String id,
                               byte[] chunk) {
        requireTusResumable(tusResumable);
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return sessionCommandService.patch(operatorIdHolder.get(), id, uploadOffset, chunk, locale)
                .map(newOffset -> Response.noContent()
                        .header("Tus-Resumable", TUS_VERSION)
                        .header("Upload-Offset", newOffset)
                        .build());
    }

    // ========== DELETE（终止） ==========

    @DELETE
    @Path("/{id}")
    public Uni<Response> delete(@HeaderParam("Tus-Resumable") String tusResumable,
                               @PathParam("id") String id) {
        requireTusResumable(tusResumable);
        return sessionCommandService.delete(operatorIdHolder.get(), id,
                        httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE))
                .map(v -> Response.noContent().header("Tus-Resumable", TUS_VERSION).build());
    }

    // ========== 辅助 ==========

    private void requireTusResumable(String tusResumable) {
        if (tusResumable == null || !TUS_VERSION.equals(tusResumable.trim())) {
            throw new TusProtocolException(412, TusProtocolException.Reason.BAD_TUS_RESUMABLE);
        }
    }

    /**
     * 解析 tus Upload-Metadata 头：{@code key value(base64),key value(base64),...}
     * 返回解码后的 key→value 映射。
     */
    private Map<String, String> parseUploadMetadata(String header) {
        Map<String, String> map = new LinkedHashMap<>();
        if (header == null || header.isBlank()) {
            return map;
        }
        for (String pair : header.split(",")) {
            String[] kv = pair.trim().split(" ", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                map.put(kv[0].trim(),
                        new String(Base64.getDecoder().decode(kv[1].trim()), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // base64 非法：忽略该 key
            }
        }
        return map;
    }

    /**
     * 解析 tus {@code metadata} 键值：{@code parseUploadMetadata} 已完成 header 级 base64 解码，
     * 此处拿到的是原始 metadata JSON（与普通上传的 {@code metadata} 表单字段同形态）。
     * <p>按用途选择反序列化目标与严格性：</p>
     * <ul>
     *   <li>BATCH_IMPORT（既有行为）：{@code UploadDocumentCommand} → toImportMetadata；
     *       null/空/解析失败=继承批次 default_metadata（留 warn）——批次 default 可兜底；
     *       注意历史实现曾在此对已解码 JSON 二次 base64 解码并静默吞错（现留 warn）。</li>
     *   <li>DOC_EDIT：{@code EditDocumentCommand} → toImportMetadata；null/空=仅换文件不改属性（合法）；
     *       JSON 非法 → A0400（无批次默认兜底，静默吞会让用户以为元数据已生效）。</li>
     *   <li>VERSION_OVERRIDE：{@code UploadDocumentCommand} → toImportMetadata；JSON 非法 → A0400
     *       （version 必填的缺失由服务层拒绝）。</li>
     * </ul>
     */
    private Optional<ImportFileMetadata> parseMetadataJson(String metadataJson, UploadSessionPurpose purpose) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Optional.empty();
        }
        try {
            ImportFileMetadata parsed = switch (purpose) {
                case DOC_EDIT -> objectMapper.readValue(metadataJson, EditDocumentCommand.class)
                        .toImportMetadata();
                case BATCH_IMPORT, VERSION_OVERRIDE -> objectMapper
                        .readValue(metadataJson, UploadDocumentCommand.class).toImportMetadata();
            };
            return Optional.of(parsed);
        } catch (JsonProcessingException e) {
            String field = DocumentResource.extractMetadataFieldName(e);
            logger.error("Failed to parse tus doc-purpose metadata JSON: purpose={}, field={}, error={}",
                    purpose, field, e.getOriginalMessage());
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400,
                    appMessagesForError().metadataFieldUnrecognized(field));
        }
    }

    /** 从当前请求解析 Accept-Language 的消息源（metadata 解析错误文案用） */
    private AppMessages appMessagesForError() {
        return LocaleUtil.getAppMessage(httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE));
    }

    private Optional<String> httpDate(LocalDateTime ldt) {
        return ldt == null ? Optional.empty()
                : Optional.of(ldt.atZone(ZoneId.systemDefault()).format(HTTP_DATE));
    }
}
