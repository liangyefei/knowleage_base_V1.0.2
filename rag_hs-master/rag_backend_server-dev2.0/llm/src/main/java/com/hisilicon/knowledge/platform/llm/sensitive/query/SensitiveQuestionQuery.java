package com.hisilicon.knowledge.platform.llm.sensitive.query;

import java.time.LocalDateTime;

/**
 * 不合规提问记录查询条件对象（G.MET.01：>5 参数收敛为查询对象）
 *
 * @param account  账号模糊匹配（可选）
 * @param label    标签标识精确匹配（可选）
 * @param beginTime 请求时间下界（含，可选）
 * @param endTime   请求时间上界（含，可选）
 * @param page      页码（1 起，可空默认 1）
 * @param pageSize  每页条数（可空默认 20，上限 1000）
 */
public record SensitiveQuestionQuery(String account, String label,
                                     LocalDateTime beginTime, LocalDateTime endTime,
                                     Integer page, Integer pageSize) {
}
