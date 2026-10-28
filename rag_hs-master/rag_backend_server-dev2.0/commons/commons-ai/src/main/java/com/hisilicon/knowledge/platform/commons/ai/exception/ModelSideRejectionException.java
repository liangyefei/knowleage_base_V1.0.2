package com.hisilicon.knowledge.platform.commons.ai.exception;

/**
 * 模型侧拒绝生成异常（SSE 流内识别到 {@code {"error":..}} 错误帧时抛出，如模型内容安全拦截）
 *
 * <p>由流管线转平台自有事件：内容安全 → {@code event:sensitive}（A0431 + i18n 文案）；
 * 其它模型错误 → {@code event:error}（C0001）。生成终止、该回答不落库。</p>
 */
public class ModelSideRejectionException extends RuntimeException {

    /** 是否为模型内容安全拦截（true 下发 sensitive 事件，false 下发通用 error 事件） */
    private final boolean contentSafety;

    public ModelSideRejectionException(String message, boolean contentSafety) {
        super(message);
        this.contentSafety = contentSafety;
    }

    public boolean isContentSafety() {
        return contentSafety;
    }
}