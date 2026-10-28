package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 文档状态不正确异常（状态机非法转换，如对 DRAFT 文档执行 approve）
 */
public class DocumentStateException extends BusinessException {

    public DocumentStateException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0508, message);
    }
}
