package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * tus 上传会话 ID 值对象
 *
 * @param value 会话 ID 字符串（由 {@link IdGenerator#uploadSession()} 生成）
 */
public record UploadSessionId(String value) {

    public UploadSessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("上传会话 ID 不能为空");
        }
    }

    public static UploadSessionId of(String value) {
        return new UploadSessionId(value);
    }
}
