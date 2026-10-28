package com.hisilicon.knowledge.platform.llm.sensitive.query.dto;

/**
 * 敏感词分类词数统计项（管理页侧边栏）
 *
 * @param tagId               标签 id
 * @param textLabel           文本标签
 * @param labelIdentification 标签标识
 * @param status              标签状态（1=启用 / 2=停用；软删标签不参与统计）
 * @param wordCount           该分类下的敏感词数（含启用与停用词；词为硬删，无软删态）
 */
public record SensitiveTagWordCount(String tagId, String textLabel, String labelIdentification,
                                    Short status, long wordCount) {
}
