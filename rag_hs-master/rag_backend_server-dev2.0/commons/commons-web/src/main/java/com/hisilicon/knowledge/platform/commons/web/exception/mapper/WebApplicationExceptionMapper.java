package com.hisilicon.knowledge.platform.commons.web.exception.mapper;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
    private static final Logger logger = LoggerFactory.getLogger(WebApplicationExceptionMapper.class);
    @Context
    HttpHeaders httpHeaders;
    @Context
    jakarta.ws.rs.core.Request request;
    @Context
    jakarta.ws.rs.core.UriInfo uriInfo;

    @Override
    public Response toResponse(WebApplicationException e) {
        logger.error("Error accessing web service", e);
        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE)
        );

        if (e instanceof NotAllowedException) {
            // http方法不支持
            return buildJson(Response.Status.METHOD_NOT_ALLOWED, ErrorCodeEnum.USER_ERROR_A0422,
                    appMessages.methodNotAllowed(), null);
        }

        if (e instanceof NotFoundException notFoundException) {
            return notFound(notFoundException, appMessages);
        }

        // 其他 WebApplicationException（如 RESTEasy JSON 解析失败抛 400）：
        // 4xx 透传状态码 + 统一 JSON（参数格式错误 A0421），5xx 保持 500
        int status = e.getResponse() != null ? e.getResponse().getStatus() : 0;
        if (status >= 400 && status < 500) {
            return buildJson(Response.Status.fromStatusCode(status), ErrorCodeEnum.USER_ERROR_A0421,
                    appMessages.invalidParameter(), MediaType.APPLICATION_JSON_TYPE);
        }

        return buildJson(Response.Status.INTERNAL_SERVER_ERROR, ErrorCodeEnum.SYSTEM_ERROR_B0001,
                appMessages.accessWebServiceFail(), MediaType.APPLICATION_JSON_TYPE);
    }

    /** 访问资源不存在：记录请求关键信息后按成因拆 400/404 */
    private Response notFound(NotFoundException e, AppMessages appMessages) {
        // 记录请求关键信息（method/path/Content-Type）：网关转发的异常请求（如 PATCH 的
        // Content-Type 被改写、健康检查探测错误路径）到达后端时，仅凭堆栈无法定位来源
        String method = null;
        String path = null;
        MediaType contentType = null;
        try {
            method = request.getMethod();
            path = uriInfo == null ? null : uriInfo.getPath();
            contentType = httpHeaders.getMediaType();
        } catch (RuntimeException ignored) {
            // 映射器在无请求上下文时被调用：保持 null，不影响原错误响应
        }
        logger.error("Error accessing web service: method={}, path={}, contentType={} (stack trace in previous log entry)",
                method, path, contentType);

        Throwable cause = e.getCause();
        if (cause instanceof NumberFormatException) {
            // 如果是由类型转换错误引起的，返回400，并给出清晰的提示
            return buildJson(Response.Status.BAD_REQUEST, ErrorCodeEnum.USER_ERROR_A0422,
                    appMessages.invalidParameter(), null);
        }
        // 其他真正的资源未找到情况，保持原有的404处理
        return buildJson(Response.Status.NOT_FOUND, ErrorCodeEnum.USER_ERROR_A0422,
                appMessages.resourceNotFind(), null);
    }

    /** 统一 JSON 错误响应体；contentType 为空时省略 Content-Type（沿用原响应默认） */
    private static Response buildJson(Response.Status status, ErrorCodeEnum code,
                                      String message, MediaType contentType) {
        Response.ResponseBuilder builder = Response.status(status)
                .entity(ResponseResult.<Void>builder()
                        .error(code)
                        .message(message)
                        .build());
        if (contentType != null) {
            builder.type(contentType);
        }
        return builder.build();
    }
}
