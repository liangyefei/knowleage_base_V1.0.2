package com.hisilicon.knowledge.platform.commons.ai.dto;

/**
 * 模型服务响应信封
 * <p>对应模型服务 BaseResponse：{code, msg, data}。</p>
 */
public record ModelApiResponse(int code, String msg, Object data) {
    public boolean isSuccess() {
        return code == 200;
    }
}