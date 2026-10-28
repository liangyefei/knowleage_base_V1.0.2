package com.hisilicon.knowledge.platform.llm.exception;

/**
 * 会话文件存储异常（本地磁盘读写失败）。
 *
 * <p>G.ERR.05：基础设施层失败必须抛出与抽象层次相符的专用异常，不得抛裸
 * {@link RuntimeException}/{@link IllegalStateException}；保留原始 {@link java.io.IOException}
 * 作为 cause，便于定位磁盘/权限问题。对外仍由全局异常处理映射为系统错误（500）。</p>
 */
public class ConversationFileStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConversationFileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
