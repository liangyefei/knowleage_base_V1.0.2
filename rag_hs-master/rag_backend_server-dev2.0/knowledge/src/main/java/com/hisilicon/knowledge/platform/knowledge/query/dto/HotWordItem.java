package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * 热门词列表项（分页查询）
 *
 * @param id        热门词 ID
 * @param content   热门词内容
 * @param kbId      所属知识库 ID
 * @param kbName    所属知识库名称（管理列表展示用）
 * @param sortOrder 排序号
 * @param status    状态：1=启用 / 0=停用
 * @param updatedAt 最近更新时间
 */
public record HotWordItem(
        String id,
        String content,
        String kbId,
        String kbName,
        Integer sortOrder,
        Short status,
        LocalDateTime updatedAt) {
}
