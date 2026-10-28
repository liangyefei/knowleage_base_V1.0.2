package com.hisilicon.knowledge.platform.commons.web.exception.mapper;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Provider
public class NullPointerExceptionMapper implements ExceptionMapper<NullPointerException> {
    private static final Logger logger = LoggerFactory.getLogger(NullPointerExceptionMapper.class);

    @Context
    HttpHeaders httpHeaders;

    @Override
    public Response toResponse(NullPointerException e) {
        logger.error("Null pointer error", e);

        // 识别特定错误
        if (e.getMessage() != null && e.getMessage().contains("Cannot invoke")
                && e.getMessage().contains("because") && e.getMessage().contains("is null")) {
            AppMessages appMessages = LocaleUtil.getAppMessage(
                    httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE)
            );

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ResponseResult.<Void>builder()
                            .error(ErrorCodeEnum.USER_ERROR_A0410)
                            .message(appMessages.noParameters())
                            .build())
                    .build();
        }
        // 其他IO异常返回500
        return Response.serverError().type(MediaType.APPLICATION_JSON).build();
    }
}
