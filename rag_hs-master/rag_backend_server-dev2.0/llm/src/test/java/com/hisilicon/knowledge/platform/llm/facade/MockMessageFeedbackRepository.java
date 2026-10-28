package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.llm.domain.entity.MessageFeedbackEntity;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.MessageFeedbackRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动 Mock — 替换 {@link MessageFeedbackRepositoryImpl}
 */
public class MockMessageFeedbackRepository extends MessageFeedbackRepositoryImpl {

    /** 内存存储：messageId -> 反馈 */
    private static final Map<String, MessageFeedbackEntity> STORE = new ConcurrentHashMap<>();
    /** 最近一次 persist 的反馈 */
    private static volatile MessageFeedbackEntity persistedFeedback;

    public MockMessageFeedbackRepository() {
        super(null);
    }

    public static MessageFeedbackEntity getPersistedFeedback() {
        return persistedFeedback;
    }

    /** 直接操作内存存储（历史回显用例预置反馈） */
    public static Map<String, MessageFeedbackEntity> store() {
        return STORE;
    }

    public static void reset() {
        STORE.clear();
        persistedFeedback = null;
    }

    @Override
    public Uni<MessageFeedbackEntity> findByMessageAndUser(String messageId, String userId) {
        return Uni.createFrom().item(STORE.get(messageId));
    }

    @Override
    public Uni<List<MessageFeedbackEntity>> findByMessageIdsAndUser(List<String> messageIds, String userId) {
        return Uni.createFrom().item(messageIds.stream()
                .map(STORE::get)
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    @Override
    public Uni<MessageFeedbackEntity> persist(MessageFeedbackEntity entity) {
        STORE.put(entity.getMessageId(), entity);
        persistedFeedback = entity;
        return Uni.createFrom().item(entity);
    }
}
