package com.hisilicon.knowledge.platform.commons.errorcode.exception;


import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;

import java.io.Serial;

/**
 *
 * @apiNote 业务处理异常类，表示在处理业务逻辑时发生错误，错误码参见{@link ErrorCodeEnum}
 *
 */
public class BusinessException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 7134897190746766939L;
    protected final ErrorCodeEnum errorCodeEnum;

    public BusinessException(ErrorCodeEnum errorCodeEnum) {
        super(errorCodeEnum.getDescribe());
        this.errorCodeEnum = errorCodeEnum;
    }

    public BusinessException(ErrorCodeEnum errorCodeEnum, String message) {
        super(message);
        this.errorCodeEnum = errorCodeEnum;
    }

    public BusinessException(ErrorCodeEnum errorCodeEnum, String message, Throwable cause) {
        super(message, cause);
        this.errorCodeEnum = errorCodeEnum;
    }

    public BusinessException(ErrorCodeEnum errorCodeEnum, Throwable cause) {
        super(errorCodeEnum.getDescribe(), cause);
        this.errorCodeEnum = errorCodeEnum;
    }

    public ErrorCodeEnum getErrorCodeEnum() {
        return errorCodeEnum;
    }
}
