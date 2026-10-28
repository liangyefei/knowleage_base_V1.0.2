package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 热门词内容重复异常
 * <p>同一知识库内热门词内容唯一。</p>
 */
public class HotWordDuplicateException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public HotWordDuplicateException(String message) {
        super(ErrorCodeEnum.HOTWORD_DUPLICATE, message);
    }
}
