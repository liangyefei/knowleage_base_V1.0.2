package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 非法的工单状态。
 */
public class BadcaseInvalidStatusException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public BadcaseInvalidStatusException(String message) {
        super(ErrorCodeEnum.BADCASE_INVALID_STATUS, message);
    }
}
