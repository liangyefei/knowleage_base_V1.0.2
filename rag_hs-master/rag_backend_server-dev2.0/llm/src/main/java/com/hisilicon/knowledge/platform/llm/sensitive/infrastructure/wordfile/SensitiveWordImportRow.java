package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile;

/**
 * 敏感词导入文件行（xlsx / csv 解析出的原始字符串）
 *
 * @param textLabel     标签名称（必填）
 * @param identification 标签标识（可空，空则默认取标签名称；匹配到既有标签时忽略）
 * @param tagEnableRaw  标签是否启用（原始值：启用/停用，空默认启用）
 * @param word          敏感词（必填）
 * @param wordEnableRaw 词是否启用（原始值：启用/停用，空默认启用）
 */
public record SensitiveWordImportRow(String textLabel,
                                     String identification,
                                     String tagEnableRaw,
                                     String word,
                                     String wordEnableRaw) {
}
