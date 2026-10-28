package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 该冲突已提交 Badcase 工单，请勿重复提交。
 */
public class ConflictBadcaseDuplicateException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictBadcaseDuplicateException(String message) {
        super(ErrorCodeEnum.CONFLICT_BADCASE_DUPLICATE, message);
    }
}
