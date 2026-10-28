package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.knowledge.exception.TusProtocolException;
import jakarta.ws.rs.core.Context;
import java.util.List;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * tus 协议异常映射器：保留真实 HTTP 状态码（tus 客户端重试语义依赖），
 * body 出统一 envelope + 具体业务码与 i18n 文案——替代裸 {@code WebApplicationException}
 * 被通用映射器压成 {@code A0421 参数格式不匹配} 的旧行为。
 * <p>JAX-RS 按异常类型就近选择 mapper，本 {@code @Provider} 优先于通用
 * {@code WebApplicationExceptionMapper}（TusProtocolException 不是其子类，天然不冲突）。</p>
 */
@Provider
public class TusProtocolExceptionMapper implements ExceptionMapper<TusProtocolException> {

    @Context
    HttpHeaders httpHeaders;

    @Override
    public Response toResponse(TusProtocolException e) {
        AppMessages msg = LocaleUtil.getAppMessage(httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE));
        List<Object> args = e.getArgs();
        // 文案：抛出点已解析的优先；否则按 reason 默认模板兜底
        String message = e.getResolvedMessage() != null ? e.getResolvedMessage()
                : switch (e.getReason()) {
                    case OFFSET_CONFLICT -> msg.tusOffsetConflict(
                            String.valueOf(args.get(0)), String.valueOf(args.get(1)));
                    case SESSION_ABORTED -> msg.tusSessionAborted();
                    case SESSION_EXPIRED -> msg.tusSessionExpired();
                    case SESSION_FAILED, COMPLETE_FAILED ->
                            msg.tusSessionFailed(args.isEmpty() ? "" : String.valueOf(args.get(0)));
                    case SESSION_PROCESSING -> msg.tusSessionProcessing();
                    case SESSION_NOT_FOUND -> msg.tusSessionNotFound();
                    case BAD_TUS_RESUMABLE -> msg.tusResumableInvalid();
                };
        Response.ResponseBuilder rb = Response.status(e.getStatus())
                .header("Tus-Resumable", "1.0.0")
                .entity(ResponseResult.<Void>builder()
                        .error(e.errorCode())
                        .message(message)
                        .build())
                .type(MediaType.APPLICATION_JSON);
        // 纠偏偏移头：断点冲突（服务端真实断点）与完成失败（全长）统一由此字段承载，
        // 客户端/排障可据此一步对齐，省一次 HEAD
        if (e.getUploadOffsetHeader() != null) {
            rb.header("Upload-Offset", String.valueOf(e.getUploadOffsetHeader()));
        }
        return rb.build();
    }
}
