package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 热门词启用数量超限异常
 * <p>每个知识库启用中的热门词最多 5 条。</p>
 */
public class HotWordEnabledLimitException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public HotWordEnabledLimitException(String message) {
        super(ErrorCodeEnum.HOTWORD_ENABLED_LIMIT, message);
    }
}
