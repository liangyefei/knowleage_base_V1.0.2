package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 该范围已有检测任务进行中。
 */
public class ConflictScanAlreadyRunningException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictScanAlreadyRunningException(String message) {
        super(ErrorCodeEnum.CONFLICT_SCAN_ALREADY_RUNNING, message);
    }
}
