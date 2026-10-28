package com.hisilicon.knowledge.platform.llm.sensitive.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 敏感词标签名重复（A0429）
 */
public class SensitiveTagNameDuplicateException extends BusinessException {

    public SensitiveTagNameDuplicateException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0429, message);
    }
}
