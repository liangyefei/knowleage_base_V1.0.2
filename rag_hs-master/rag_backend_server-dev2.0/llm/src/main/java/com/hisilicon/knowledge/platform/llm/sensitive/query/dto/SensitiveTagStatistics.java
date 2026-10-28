package com.hisilicon.knowledge.platform.llm.sensitive.query.dto;

import java.util.List;

/**
 * 敏感词分类统计（管理页侧边栏：全部分类词数 + 每个分类词数）
 *
 * @param total 全部未删除分类下的敏感词总数（恒等于 items 的 wordCount 之和）
 * @param items 各分类词数（按标签创建时间升序；无词分类保留 wordCount=0）
 */
public record SensitiveTagStatistics(long total, List<SensitiveTagWordCount> items) {
}
