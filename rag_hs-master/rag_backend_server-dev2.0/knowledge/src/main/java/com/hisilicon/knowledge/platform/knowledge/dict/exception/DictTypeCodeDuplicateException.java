package com.hisilicon.knowledge.platform.knowledge.dict.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 字典类型编码重复异常
 */
public class DictTypeCodeDuplicateException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public DictTypeCodeDuplicateException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0429, message);
    }
}
