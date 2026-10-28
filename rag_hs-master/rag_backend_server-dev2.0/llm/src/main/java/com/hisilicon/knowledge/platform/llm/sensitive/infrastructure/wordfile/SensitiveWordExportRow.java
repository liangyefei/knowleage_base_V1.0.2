package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile;

/**
 * 敏感词导出行（词 + 标签信息组装，导入导出同构可回导）
 *
 * @param textLabel          标签名称
 * @param labelIdentification 标签标识
 * @param tagStatus          标签状态（1=启用 / 2=停用）
 * @param word               敏感词
 * @param wordStatus         词状态（1=启用 / 2=停用）
 */
public record SensitiveWordExportRow(String textLabel,
                                     String labelIdentification,
                                     Short tagStatus,
                                     String word,
                                     Short wordStatus) {
}
