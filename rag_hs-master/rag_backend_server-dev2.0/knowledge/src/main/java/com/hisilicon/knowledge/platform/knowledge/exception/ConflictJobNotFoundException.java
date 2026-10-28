package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 冲突检测任务不存在。
 */
public class ConflictJobNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictJobNotFoundException(String message) {
        super(ErrorCodeEnum.CONFLICT_JOB_NOT_FOUND, message);
    }
}
