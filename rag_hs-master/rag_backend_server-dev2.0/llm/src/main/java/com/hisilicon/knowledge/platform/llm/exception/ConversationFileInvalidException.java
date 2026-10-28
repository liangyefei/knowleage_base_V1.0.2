package com.hisilicon.knowledge.platform.llm.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 会话文件校验异常（大小超限 / 类型不支持）
 */
public class ConversationFileInvalidException extends BusinessException {
    public ConversationFileInvalidException(ErrorCodeEnum errorCodeEnum, String message) {
        super(errorCodeEnum, message);
    }
}
