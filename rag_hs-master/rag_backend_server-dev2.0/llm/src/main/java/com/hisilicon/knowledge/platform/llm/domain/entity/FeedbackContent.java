package com.hisilicon.knowledge.platform.llm.domain.entity;

/**
 * 回答反馈内容（评级 + 没帮助原因 + 描述）。
 *
 * <p>G.MET.01：把反馈内容收敛为领域值对象；反馈目标（消息/会话/用户）另由
 * {@link MessageFeedbackEntity} 的工厂参数承载。</p>
 *
 * @param rating  评级：HELPFUL / UNHELPFUL
 * @param reasons 没帮助原因，逗号分隔（HELPFUL 为 null）
 * @param comment 具体描述（可空）
 */
public record FeedbackContent(String rating, String reasons, String comment) {
}
