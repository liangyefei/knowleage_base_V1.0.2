package com.hisilicon.knowledge.platform.llm.domain.entity;

/**
 * 回答反馈评级常量
 * <p>前端对 ASSISTANT 回答的反馈：有帮助 / 没帮助。仿 {@link ConversationMessageEntity} 角色常量写法。</p>
 */
public final class FeedbackRating {

    /** 有帮助 */
    public static final String HELPFUL = "HELPFUL";

    /** 没帮助 */
    public static final String UNHELPFUL = "UNHELPFUL";

    private FeedbackRating() {
    }

    /**
     * 是否合法评级值
     */
    public static boolean isValid(String rating) {
        return HELPFUL.equals(rating) || UNHELPFUL.equals(rating);
    }
}
