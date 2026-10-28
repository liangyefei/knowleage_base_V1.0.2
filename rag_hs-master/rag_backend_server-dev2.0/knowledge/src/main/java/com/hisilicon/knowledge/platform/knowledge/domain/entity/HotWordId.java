package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 热门词 ID 值对象
 *
 * @param value 热门词 ID 字符串（{@code hw} + 32hex，由 {@link IdGenerator#hotWord()} 生成）
 */
public record HotWordId(String value) {

    public HotWordId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("热门词 ID 不能为空");
        }
    }

    public static HotWordId of(String value) {
        return new HotWordId(value);
    }
}
