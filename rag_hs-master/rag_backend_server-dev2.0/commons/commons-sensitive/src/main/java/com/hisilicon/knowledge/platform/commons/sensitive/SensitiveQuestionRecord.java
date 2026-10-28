package com.hisilicon.knowledge.platform.commons.sensitive;

import java.time.LocalDateTime;

/**
 * 不合规提问记录（提问侧拦截后由调用方提交入库）
 *
 * @param question            用户提问内容
 * @param account             提问账号
 * @param word                命中的敏感词
 * @param textLabel           所属文本标签
 * @param labelIdentification 标签标识
 * @param requestTime         请求时间
 */
public record SensitiveQuestionRecord(String question, String account, String word,
                                      String textLabel, String labelIdentification,
                                      LocalDateTime requestTime) {
}
