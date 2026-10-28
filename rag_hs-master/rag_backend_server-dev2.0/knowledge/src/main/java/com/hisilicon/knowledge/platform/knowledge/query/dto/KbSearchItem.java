package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * 知识库列表项 DTO
 *
 * @param kbId          知识库 ID
 * @param kbName        知识库名称
 * @param description   描述
 * @param status        状态
 * @param createdBy     创建者用户 ID（前端可另行批量查询用户名）
 * @param createdAt     创建时间
 * @param fileCount     知识库下文档数
 * @param categoryCount 知识库下分类数
 */
public record KbSearchItem(String kbId, String kbName, String description, Short status,
                           String createdBy, LocalDateTime createdAt,
                           long fileCount, long categoryCount) {
}
