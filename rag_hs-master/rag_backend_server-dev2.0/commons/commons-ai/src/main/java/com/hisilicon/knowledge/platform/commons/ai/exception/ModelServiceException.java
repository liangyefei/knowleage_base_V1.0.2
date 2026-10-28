package com.hisilicon.knowledge.platform.commons.ai.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 模型服务调用异常（C 类：调用第三方服务出错）
 */
public class ModelServiceException extends BusinessException {

    public ModelServiceException(String message) {
        super(ErrorCodeEnum.SERVICE_ERROR_C0001, message);
    }

    public ModelServiceException(String message, Throwable cause) {
        super(ErrorCodeEnum.SERVICE_ERROR_C0001, message, cause);
    }
}