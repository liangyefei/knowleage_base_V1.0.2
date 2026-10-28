package com.hisilicon.knowledge.platform.knowledge.exception;

/**
 * 本地文件存储层失败（读写/移动/回收站等 IO 异常的语义化封装）。
 * <p>G.ERR.05：禁止直接抛裸 {@code RuntimeException}——抛出异常应与方法所处抽象层次对应，
 * 存储实现层统一抛本异常，由上层映射为业务错误码。</p>
 */
public class FileStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
