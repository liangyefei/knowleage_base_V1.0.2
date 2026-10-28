package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.ConversationMessageRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动 Mock — 替换 {@link ConversationMessageRepositoryImpl}
 */
public class MockConversationMessageRepository extends ConversationMessageRepositoryImpl {

    /** findRecent/findByConversationId 返回列表 */
    private static volatile List<ConversationMessageEntity> messages;
    /** countByConversationId 返回值 */
    private static volatile long messageCount;
    /** 最近一次 persist 的消息 */
    private static volatile ConversationMessageEntity persistedMessage;
    /** 本轮全部 persist 成功的消息（按序，供按角色断言） */
    private static final List<ConversationMessageEntity> persistedAll = new ArrayList<>();
    /** 最近一次删除的会话 ID */
    private static volatile String deletedConversationId;
    /** persist 抛错开关（模拟失败） */
    private static volatile boolean failPersist;
    /** 仅 ASSISTANT persist 抛错开关（模拟流结束回答落库失败，USER 落库不受影响） */
    private static volatile boolean failAssistantPersist;

    public MockConversationMessageRepository() {
        super(null);
    }

    public static void setMessages(List<ConversationMessageEntity> list) {
        messages = list;
    }

    public static void setMessageCount(long count) {
        messageCount = count;
    }

    public static ConversationMessageEntity getPersistedMessage() {
        return persistedMessage;
    }

    /**
     * 是否已有指定角色（USER/ASSISTANT）的消息 persist 成功
     */
    public static boolean hasPersistedRole(String role) {
        return persistedAll.stream().anyMatch(m -> role.equals(m.getRole()));
    }

    public static String getDeletedConversationId() {
        return deletedConversationId;
    }

    public static void setFailPersist(boolean fail) {
        failPersist = fail;
    }

    public static void setFailAssistantPersist(boolean fail) {
        failAssistantPersist = fail;
    }

    public static void reset() {
        messages = null;
        messageCount = 0;
        persistedMessage = null;
        persistedAll.clear();
        deletedConversationId = null;
        failPersist = false;
        failAssistantPersist = false;
    }

    @Override
    public Uni<ConversationMessageEntity> findById(String messageId) {
        if (messages == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(messages.stream()
                .filter(m -> m.getId().value().equals(messageId))
                .findFirst()
                .orElse(null));
    }

    @Override
    public Uni<ConversationMessageEntity> persist(ConversationMessageEntity entity) {
        if (failPersist || (failAssistantPersist
                && ConversationMessageEntity.ROLE_ASSISTANT.equals(entity.getRole()))) {
            return Uni.createFrom().failure(new RuntimeException("mock persist failure"));
        }
        persistedMessage = entity;
        persistedAll.add(entity);
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<List<ConversationMessageEntity>> findByConversationId(ConversationId conversationId, int offset, int limit) {
        if (messages == null) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.createFrom().item(new ArrayList<>(messages));
    }

    @Override
    public Uni<Long> countByConversationId(ConversationId conversationId) {
        return Uni.createFrom().item(messageCount);
    }

    @Override
    public Uni<List<ConversationMessageEntity>> findRecent(ConversationId conversationId, int limit) {
        if (messages == null) {
            return Uni.createFrom().item(List.of());
        }
        List<ConversationMessageEntity> result = new ArrayList<>(messages);
        java.util.Collections.reverse(result);
        return Uni.createFrom().item(result);
    }

    @Override
    public Uni<Void> deleteByConversationId(ConversationId conversationId) {
        deletedConversationId = conversationId.value();
        return Uni.createFrom().voidItem();
    }
}
