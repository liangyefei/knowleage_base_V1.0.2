package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 冲突记录不存在异常。
 */
public class ConflictNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictNotFoundException(String message) {
        super(ErrorCodeEnum.CONFLICT_NOT_FOUND, message);
    }
}
