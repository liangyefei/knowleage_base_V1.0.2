package com.hisilicon.knowledge.platform.llm.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 会话不存在异常（含已归档视同不存在、越权访问他人会话）
 */
public class ConversationNotFoundException extends BusinessException {
    public ConversationNotFoundException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0430, message);
    }
}
