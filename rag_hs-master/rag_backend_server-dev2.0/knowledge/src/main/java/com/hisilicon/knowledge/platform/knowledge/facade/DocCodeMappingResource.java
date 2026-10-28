package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.knowledge.command.DocCodeMappingCommand;
import com.hisilicon.knowledge.platform.knowledge.query.DocCodeMappingQueryService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代码↔文档映射 REST 接口（前端流程最后一步：调用模型 /doc_code 返回映射结果）。
 *
 * <p>前端流程：选方向（{@code GET /kb/documents/file-options}）→ 选知识库/目录（navTree）→
 * 按名称搜索文件 → 选版本（{@code GET /kb/documents/versions}）→ 本接口发起映射。</p>
 */
@Path("/kb/doc-code-mapping")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DocCodeMappingResource {

    private static final Logger logger = LoggerFactory.getLogger(DocCodeMappingResource.class);

    private final DocCodeMappingQueryService docCodeMappingQueryService;

    @Context
    HttpHeaders httpHeaders;

    public DocCodeMappingResource(DocCodeMappingQueryService docCodeMappingQueryService) {
        this.docCodeMappingQueryService = docCodeMappingQueryService;
    }

    /**
     * 代码↔文档映射查询。
     *
     * <p>服务层按与 download 相同的版本解析口径解析所选版本的文件全路径
     * （version==currentVersion 取 documents.file_path；历史版取 document_versions.file_path，
     * 版本不存在 A0430、已下线 A0508），再裁掉 {@code kb/{KB目录段}/} 存储前缀转为模型项目内
     * 相对路径，作为 dual-mcp {@code POST /doc_code} 的 {@code file}（{@code project=kbId}）。</p>
     *
     * <p><b>版本差异仅体现在传入的 file_path 上</b>：模型 /doc_code 无 version 入参，
     * 覆盖版本后旧版本文件路径仅存在于归档表，按所选版本解析出对应存储路径即命中该版本。</p>
     *
     * <p>响应：平台统一 {@code ResponseResult} 信封——成功（HTTP 200，含空结果 count=0 + hint）
     * 按成功返回，模型响应随 {@code data} 透传；模型 404（项目图谱不存在）→ A0430、
     * 400（参数非法）→ A0420、其余模型/网络错误 → C0001，均经统一异常处理生成非 0 code。
     * direction 非法（非 doc2code/code2doc）→ A0420。Accept-Language 全链路 i18n。</p>
     *
     * @param command documentId/version/direction 必填；withSource/withDoc/limit 可选
     */
    @POST
    public Uni<ResponseResult<DocCodeMappingResult>> map(
            @Valid @NotNull(message = "{app.common.body.notnull}") DocCodeMappingCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Code-document mapping request, documentId={}, version={}, direction={}, limit={}, locale={}",
                command.documentId(), command.version(), command.direction(), command.limit(), locale);
        return docCodeMappingQueryService.map(command, locale)
                .map(result -> ResponseResult.ok(result, locale));
    }
}
