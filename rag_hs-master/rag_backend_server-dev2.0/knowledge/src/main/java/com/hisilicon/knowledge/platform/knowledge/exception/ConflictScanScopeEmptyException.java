package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 所选范围内没有可检测的已发布知识片段。
 */
public class ConflictScanScopeEmptyException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictScanScopeEmptyException(String message) {
        super(ErrorCodeEnum.CONFLICT_SCAN_SCOPE_EMPTY, message);
    }
}
