package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 知识库不存在异常
 * <p>A 类错误码：错误来源于用户请求（请求了不存在或已删除的资源，已删除视同不存在）。</p>
 */
public class KnowledgeBaseNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public KnowledgeBaseNotFoundException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0430, message);
    }
}
