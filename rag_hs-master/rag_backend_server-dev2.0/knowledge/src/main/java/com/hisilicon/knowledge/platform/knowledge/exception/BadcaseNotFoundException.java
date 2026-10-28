package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * Badcase 工单不存在。
 */
public class BadcaseNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public BadcaseNotFoundException(String message) {
        super(ErrorCodeEnum.BADCASE_NOT_FOUND, message);
    }
}
