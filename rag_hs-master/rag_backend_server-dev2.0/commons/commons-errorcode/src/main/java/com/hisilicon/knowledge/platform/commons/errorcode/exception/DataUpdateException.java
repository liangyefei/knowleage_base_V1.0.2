package com.hisilicon.knowledge.platform.commons.errorcode.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;

import java.io.Serial;

/**
 *
 * @apiNote 数据更新异常类，错误码参见{@link ErrorCodeEnum#SYSTEM_ERROR_B0504}
 *
 */
public final class DataUpdateException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 9145997223748467959L;

    /**
     * 创建数据更新异常类
     * @param message 错误消息
     */
    public DataUpdateException(String message) {
        super(ErrorCodeEnum.SYSTEM_ERROR_B0504, message);
    }
}
