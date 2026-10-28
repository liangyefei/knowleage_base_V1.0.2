package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 文件大小超限异常
 */
public class FileSizeExceededException extends BusinessException {

    public FileSizeExceededException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0705, message);
    }
}
