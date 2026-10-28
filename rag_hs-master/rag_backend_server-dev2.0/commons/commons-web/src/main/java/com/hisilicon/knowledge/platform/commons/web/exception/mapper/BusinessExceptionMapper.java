package com.hisilicon.knowledge.platform.commons.web.exception.mapper;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * 业务异常 Mapper — 支持国际化消息
 */
@Provider
public class BusinessExceptionMapper implements ExceptionMapper<BusinessException> {

    @Context
    HttpHeaders httpHeaders;

    @Override
    public Response toResponse(BusinessException e) {
        return Response.status(Response.Status.OK).entity(
                ResponseResult.<Void>builder()
                        .error(e.getErrorCodeEnum())
                        .message(resolveMessage(e))
                        .build())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * 根据 ErrorCodeEnum 解析国际化消息，未知错误码回退到异常自带的 message
     */
    private String resolveMessage(BusinessException e) {
        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE));

        ErrorCodeEnum code = e.getErrorCodeEnum();
        if (code == null) {
            return e.getMessage();
        }
        return switch (code) {
            case USER_ERROR_A0210 -> appMessages.loginUsernamePasswordMismatch();
            case USER_ERROR_A0202 -> appMessages.loginAccountDisabled();
            case USER_ERROR_A0204 -> appMessages.refreshTokenExpired();
            case USER_ERROR_A0340 -> appMessages.decryptFailed();
            case USER_ERROR_A0341 -> appMessages.verifySignatureFailed();
            // A0400/A0410：异常携带自定义 message（业务方已按 locale 组装）时优先透传，
            // 否则回退通用 i18n 文案（历史行为兼容：仅 code 无 message 的抛点）
            case USER_ERROR_A0400, USER_ERROR_A0410 -> (e.getMessage() != null && !e.getMessage().isBlank())
                    ? e.getMessage() : appMessages.invalidParameter();
            default -> e.getMessage();
        };
    }
}
