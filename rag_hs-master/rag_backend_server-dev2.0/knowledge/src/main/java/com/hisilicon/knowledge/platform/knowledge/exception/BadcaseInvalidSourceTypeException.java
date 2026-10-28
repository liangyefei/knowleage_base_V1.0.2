package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 来源与问题类型组合不匹配。
 */
public class BadcaseInvalidSourceTypeException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public BadcaseInvalidSourceTypeException(String message) {
        super(ErrorCodeEnum.BADCASE_INVALID_SOURCE_TYPE, message);
    }
}
