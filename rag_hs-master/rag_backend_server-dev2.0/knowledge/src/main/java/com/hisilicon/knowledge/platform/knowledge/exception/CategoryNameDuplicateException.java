package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 类别名称重复异常
 * <p>A 类错误码：错误来源于用户输入（同级类别名称与现有记录冲突）。</p>
 */
public class CategoryNameDuplicateException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CategoryNameDuplicateException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0429, message);
    }
}
