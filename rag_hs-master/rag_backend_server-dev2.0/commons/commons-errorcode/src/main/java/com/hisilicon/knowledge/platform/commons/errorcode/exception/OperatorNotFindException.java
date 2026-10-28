package com.hisilicon.knowledge.platform.commons.errorcode.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;

import java.io.Serial;

/**
 *
 * @apiNote 未发现操作员异常类，错误码参见{@link ErrorCodeEnum#USER_ERROR_A0150}
 *
 */
public final class OperatorNotFindException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 3245897223748366959L;

    /**
     * 创建未发现操作员异常类
     * @param message 错误消息
     */
    public OperatorNotFindException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0150, message);
    }
}
