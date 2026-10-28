package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 父类别不存在异常
 * <p>A 类错误码：错误来源于用户请求（父类别不存在、已删除或不属于指定知识库，均视同不存在）。</p>
 */
public class CategoryNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CategoryNotFoundException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0430, message);
    }
}
