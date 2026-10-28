package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 类别深度超限异常
 * <p>A 类错误码（A0420 参数值超出允许范围）：请求的层级深度超过 20 层限制
 * （{@code CategoryTreeService.MAX_CATEGORY_DEPTH}）。</p>
 */
public class CategoryDepthExceededException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CategoryDepthExceededException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0420, message);
    }
}
