package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 类别移动目标非法异常（移到自身或后代类别，形成循环引用）
 * <p>A 类错误码（A0508 用户请求资源状态不正确）：树形结构不允许移动形成环。</p>
 */
public class CategoryMoveCycleException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CategoryMoveCycleException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0508, message);
    }
}
