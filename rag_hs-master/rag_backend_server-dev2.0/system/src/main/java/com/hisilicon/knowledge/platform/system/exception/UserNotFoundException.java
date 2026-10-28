package com.hisilicon.knowledge.platform.system.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 用户未找到异常（用户名或密码不匹配）
 */
public class UserNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UserNotFoundException() {
        super(ErrorCodeEnum.USER_ERROR_A0210);
    }
}
