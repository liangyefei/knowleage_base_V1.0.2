package com.example.project.user.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

public class UserNotFindException extends BusinessException {
    public UserNotFindException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0201, message);
    }
}
