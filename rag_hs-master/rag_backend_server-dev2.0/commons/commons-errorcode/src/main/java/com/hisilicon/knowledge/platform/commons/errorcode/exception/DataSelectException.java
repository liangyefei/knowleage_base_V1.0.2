package com.hisilicon.knowledge.platform.commons.errorcode.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;

import java.io.Serial;

/**
 *
 * @apiNote 数据查询异常类，错误码参见{@link ErrorCodeEnum#SYSTEM_ERROR_B0503}
 *
 */
public final class DataSelectException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 8742997324748067919L;

    /**
     * 创建数据查询异常类
     * @param message 错误消息
     */
    public DataSelectException(String message) {
        super(ErrorCodeEnum.SYSTEM_ERROR_B0503, message);
    }
}
