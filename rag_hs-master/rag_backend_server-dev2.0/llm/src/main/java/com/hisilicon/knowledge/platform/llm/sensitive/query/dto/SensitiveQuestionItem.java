package com.hisilicon.knowledge.platform.llm.sensitive.query.dto;

import java.time.LocalDateTime;

/**
 * 不合规提问记录查询项
 *
 * @param id              记录 id
 * @param question        提问内容
 * @param account         提问账号
 * @param word            命中敏感词
 * @param label           文本标签
 * @param identification  标签标识
 * @param requestTime     请求时间
 */
public record SensitiveQuestionItem(String id, String question, String account, String word,
                                    String label, String identification, LocalDateTime requestTime) {
}
