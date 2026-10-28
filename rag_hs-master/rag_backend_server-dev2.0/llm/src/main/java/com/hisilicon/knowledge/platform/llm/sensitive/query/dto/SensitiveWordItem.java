package com.hisilicon.knowledge.platform.llm.sensitive.query.dto;

import java.time.LocalDateTime;

/**
 * 敏感词词库查询项
 *
 * @param id        词库记录 id
 * @param tagId     标签 id
 * @param textLabel 标签名称（标签缺失/已删的防御性场景返回空串）
 * @param word      敏感词
 * @param status    词状态（1=启用 / 2=停用）
 * @param createdAt 创建时间
 * @param createdBy 创建人账号（取 JWT sub；存量行为 null）
 * @param remarks   备注，可空
 */
public record SensitiveWordItem(String id, String tagId, String textLabel, String word,
                                Short status, LocalDateTime createdAt,
                                String createdBy, String remarks) {
}
