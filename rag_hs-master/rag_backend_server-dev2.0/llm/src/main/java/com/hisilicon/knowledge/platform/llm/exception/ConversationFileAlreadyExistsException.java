package com.hisilicon.knowledge.platform.llm.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 会话重复上传文件异常（每会话仅 1 个文件）
 */
public class ConversationFileAlreadyExistsException extends BusinessException {
    public ConversationFileAlreadyExistsException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0429, message);
    }
}
