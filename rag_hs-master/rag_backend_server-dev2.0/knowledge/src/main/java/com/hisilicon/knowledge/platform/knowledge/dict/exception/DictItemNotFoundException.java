package com.hisilicon.knowledge.platform.knowledge.dict.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 字典条目不存在异常（含已软删除，视同不存在）
 */
public class DictItemNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public DictItemNotFoundException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0430, message);
    }
}
