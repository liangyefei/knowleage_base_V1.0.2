package com.hisilicon.knowledge.platform.system.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 用户已被禁用异常
 */
public class UserDisabledException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UserDisabledException() {
        super(ErrorCodeEnum.USER_ERROR_A0202);
    }
}
