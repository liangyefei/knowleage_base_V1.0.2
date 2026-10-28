package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 文档不存在异常
 */
public class DocumentNotFoundException extends BusinessException {

    public DocumentNotFoundException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0430, message);
    }
}
