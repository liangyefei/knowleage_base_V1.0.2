package com.hisilicon.knowledge.platform.commons.i18n;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * @apiNote 不支持的区域异常类，错误码参见{@link ErrorCodeEnum#USER_ERROR_A0606}
 */
public final class LocaleNotSupportedException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 3245897223748366959L;

    /**
     * 创建不不支持的区域异常类
     * @param message 错误消息
     */
    public LocaleNotSupportedException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0606, message);
    }
}
