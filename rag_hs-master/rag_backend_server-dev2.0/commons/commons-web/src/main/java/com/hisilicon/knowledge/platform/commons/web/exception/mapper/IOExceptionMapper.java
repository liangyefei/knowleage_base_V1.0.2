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

import java.io.IOException;

@Provider
public class IOExceptionMapper implements ExceptionMapper<IOException> {
    private static final Logger logger = LoggerFactory.getLogger(IOExceptionMapper.class);

    @Context
    HttpHeaders httpHeaders;

    @Override
    public Response toResponse(IOException e) {
        logger.error("IO error", e);

        // 识别特定错误
        if (e.getMessage() != null && e.getMessage().contains("Connection terminated parsing multipart request")) {
            AppMessages appMessages = LocaleUtil.getAppMessage(
                    httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE)
            );

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ResponseResult.<Void>builder()
                            .error(ErrorCodeEnum.USER_ERROR_A0410)
                            .message(appMessages.fileCannotEmpty())
                            .build())
                    .build();
        }
        // 其他 IO 异常（含 REST Client 连接失败）：返回结构化 B0001（系统执行出错），
        // 不再裸 500——避免调用方收到无 code 的空响应体
        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE)
        );
        return Response.serverError().type(MediaType.APPLICATION_JSON)
                .entity(ResponseResult.<Void>builder()
                        .error(ErrorCodeEnum.SYSTEM_ERROR_B0001)
                        .message(appMessages.systemInternalError())
                        .build())
                .build();
    }
}
