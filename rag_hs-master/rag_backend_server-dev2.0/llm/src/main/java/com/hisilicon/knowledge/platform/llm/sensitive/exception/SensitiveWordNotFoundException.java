package com.hisilicon.knowledge.platform.llm.sensitive.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 敏感词不存在或已删除（A0430）
 */
public class SensitiveWordNotFoundException extends BusinessException {

    public SensitiveWordNotFoundException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0430, message);
    }
}
