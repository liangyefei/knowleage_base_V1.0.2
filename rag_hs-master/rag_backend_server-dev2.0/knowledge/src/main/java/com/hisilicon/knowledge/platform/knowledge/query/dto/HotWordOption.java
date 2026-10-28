package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 热门词下拉选项（启用中）
 *
 * @param id        热门词 ID
 * @param content   热门词内容
 * @param kbId      所属知识库 ID
 * @param sortOrder 排序号
 */
public record HotWordOption(
        String id,
        String content,
        String kbId,
        Integer sortOrder) {
}
