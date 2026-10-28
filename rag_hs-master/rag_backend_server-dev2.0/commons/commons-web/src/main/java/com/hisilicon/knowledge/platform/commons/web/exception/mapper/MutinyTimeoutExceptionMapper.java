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
import io.smallrye.mutiny.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

@Provider
public class MutinyTimeoutExceptionMapper implements ExceptionMapper<TimeoutException> {
    private static final Logger logger = LoggerFactory.getLogger(MutinyTimeoutExceptionMapper.class);

    @Context
    HttpHeaders httpHeaders;

    @Override
    public Response toResponse(TimeoutException e) {
        logger.error("", e);
        AppMessages appMessages = LocaleUtil.getAppMessage(httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE));
        String message;
        if (Arrays.stream(e.getStackTrace()).anyMatch(s -> s.toString().contains("io.quarkus.redis"))) {
            message = appMessages.redisUnavailable();
        } else {
            message = appMessages.systemResourcesUnavailable();
        }

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ResponseResult.<Void>builder().error(ErrorCodeEnum.SYSTEM_ERROR_B0320).message(message).build())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
