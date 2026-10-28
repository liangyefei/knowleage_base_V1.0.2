package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 热门词不存在异常
 * <p>A 类错误码：错误来源于用户请求（请求了不存在或已删除的热门词）。</p>
 */
public class HotWordNotFoundException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public HotWordNotFoundException(String message) {
        super(ErrorCodeEnum.HOTWORD_NOT_FOUND, message);
    }
}
