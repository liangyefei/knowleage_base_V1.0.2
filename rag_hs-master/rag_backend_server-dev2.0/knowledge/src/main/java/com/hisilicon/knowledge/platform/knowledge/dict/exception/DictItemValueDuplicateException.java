package com.hisilicon.knowledge.platform.knowledge.dict.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 字典条目值重复异常（同类型下 value 唯一）
 */
public class DictItemValueDuplicateException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public DictItemValueDuplicateException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0429, message);
    }
}
