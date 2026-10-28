package com.hisilicon.knowledge.platform.llm.exception;

/**
 * docs 事件改写失败异常（JSON 解析/序列化失败）。
 *
 * <p>G.ERR.05：抛出与抽象层次相符的专用异常，不抛裸 {@link IllegalStateException}。
 * 调用方按「非致命」处理：捕获后原样透传原始 chunk，不影响回答下发与落库。</p>
 */
public class ChatDocsRewriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChatDocsRewriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
