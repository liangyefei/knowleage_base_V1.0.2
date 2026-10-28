package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

import java.io.Serial;

/**
 * 知识图谱查询无结果异常（dual-mcp 与 MemPalace 并发查询均无结果时抛出）。
 * <p>A 类错误码 A0430：两服务并发查询均未命中（dual-mcp matches 空 且 MemPalace count=0，或服务均不可达）。</p>
 */
public class KgNoResultException extends BusinessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public KgNoResultException(String message) {
        super(ErrorCodeEnum.USER_ERROR_A0430, message);
    }
}
