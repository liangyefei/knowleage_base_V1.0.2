package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * Badcase 工单 ID 值对象。
 *
 * @param value 工单 ID 字符串（{@code bc} + 32hex，由 {@link IdGenerator#badcaseTicket()} 生成）
 */
public record BadcaseTicketId(String value) {

    public BadcaseTicketId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Badcase ticket ID 不能为空");
        }
    }

    public static BadcaseTicketId of(String value) {
        return new BadcaseTicketId(value);
    }
}
