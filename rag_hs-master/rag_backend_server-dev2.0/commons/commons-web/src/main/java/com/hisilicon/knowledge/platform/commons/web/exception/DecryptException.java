package com.hisilicon.knowledge.platform.commons.web.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * @apiNote 解密信息异常类，错误码参见{@link ErrorCodeEnum#USER_ERROR_A0220}
 */
public final class DecryptException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 8245997323748567919L;

    /**
     * 创建解密信息异常类
     * @param message 错误消息
     */
    public DecryptException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0200, message);
    }
}
