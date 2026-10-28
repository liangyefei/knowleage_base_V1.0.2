package com.hisilicon.knowledge.platform.commons.web.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;

public class UnauthorizedAccessException extends NotAuthorizedException {
    public UnauthorizedAccessException(String message) {
        super(Response.status(Response.Status.UNAUTHORIZED).entity(
                ResponseResult.<Void>builder().error(ErrorCodeEnum.USER_ERROR_A0301).message(message).build()).build());
    }
}
