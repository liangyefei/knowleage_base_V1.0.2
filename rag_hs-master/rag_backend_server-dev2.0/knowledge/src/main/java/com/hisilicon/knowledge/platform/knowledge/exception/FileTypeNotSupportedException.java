package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 文件类型不支持异常
 */
public class FileTypeNotSupportedException extends BusinessException {

    public FileTypeNotSupportedException(String message) {
        super(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE, message);
    }
}
