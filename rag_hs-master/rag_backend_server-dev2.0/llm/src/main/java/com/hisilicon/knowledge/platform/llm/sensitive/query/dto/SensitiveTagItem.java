package com.hisilicon.knowledge.platform.llm.sensitive.query.dto;

import java.time.LocalDateTime;

/**
 * 敏感词标签查询项
 *
 * @param id                  标签 id
 * @param textLabel           文本标签
 * @param labelIdentification 标签标识
 * @param remarks             说明
 * @param status              状态（0=删除,1=启用,2=禁用）
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 */
public record SensitiveTagItem(String id, String textLabel, String labelIdentification, String remarks,
                               Short status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
