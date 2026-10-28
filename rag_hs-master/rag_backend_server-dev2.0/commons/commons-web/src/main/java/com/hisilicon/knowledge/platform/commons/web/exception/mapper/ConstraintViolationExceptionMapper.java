package com.hisilicon.knowledge.platform.commons.web.exception.mapper;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.StringJoiner;

/**
 * Bean Validation 异常 Mapper — 支持国际化消息
 */
@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {
    private static final Logger logger = LoggerFactory.getLogger(ConstraintViolationExceptionMapper.class);

    @Context
    HttpHeaders httpHeaders;

    @Override
    public Response toResponse(ConstraintViolationException e) {
        logger.error("Parameter validation failed", e);

        // 收集校验消息（已由 Hibernate Validator 按 Accept-Language 插值）
        StringJoiner message = new StringJoiner(",");
        for (ConstraintViolation<?> constraintViolation : e.getConstraintViolations()) {
            message.add(constraintViolation.getMessage());
        }

        // 使用 i18n 标题
        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE));

        return Response.status(Response.Status.BAD_REQUEST).entity(
                ResponseResult.<Void>builder()
                        .error(ErrorCodeEnum.USER_ERROR_A0400)
                        .message(appMessages.invalidParameter() + ": " + message)
                        .build())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
