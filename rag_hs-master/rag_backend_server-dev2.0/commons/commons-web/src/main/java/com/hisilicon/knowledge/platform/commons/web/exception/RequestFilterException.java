package com.hisilicon.knowledge.platform.commons.web.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * @apiNote 过滤请求异常类，错误码参见{@link ErrorCodeEnum#SYSTEM_ERROR_B0001}
 */
public final class RequestFilterException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 8245997323748567919L;

    /**
     * 创建过滤请求异常类
     * @param message 错误消息
     */
    public RequestFilterException(String message) {
        super(ErrorCodeEnum.SYSTEM_ERROR_B0001, message);
    }
}
