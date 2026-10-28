package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 知识库名称重复异常
 * <p>A 类错误码：错误来源于用户输入（名称与现有记录冲突）。</p>
 */
public class KnowledgeBaseNameDuplicateException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public KnowledgeBaseNameDuplicateException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0429, message);
    }
}
