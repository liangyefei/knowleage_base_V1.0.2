package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * 自动检测开关状态 DTO。
 */
public record AutoDetectionStatusDTO(boolean enabled, LocalDateTime updatedAt) {
}
