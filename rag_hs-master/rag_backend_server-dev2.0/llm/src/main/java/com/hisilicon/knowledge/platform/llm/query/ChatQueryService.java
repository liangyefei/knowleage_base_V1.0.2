package com.hisilicon.knowledge.platform.llm.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatImageAssetUrlRewriter;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.MessageFeedbackEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationMessageRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.MessageFeedbackRepository;
import com.hisilicon.knowledge.platform.llm.exception.ConversationNotFoundException;
import com.hisilicon.knowledge.platform.llm.infrastructure.service.ChatAnswerImageRewriter;
import com.hisilicon.knowledge.platform.llm.query.dto.ConversationListItem;
import com.hisilicon.knowledge.platform.llm.query.dto.MessageListItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话查询服务（CQRS 读侧）
 * <p>历史消息出口时图片网络化：ASSISTANT content 为模型 SSE 载荷的 JSON 拼接（
 * {@code {"type":"route_reasoning",..}{"answer":..}{"answer":..}…}），其中 {@code ![alt](/kb-files/…)}
 * 引用被切碎在多个 answer 对象里。本服务读取时把 answer 值拼接成干净 markdown、全套引用现签改写为
 * 签名 URL，再回包成同形 JSON 拼接——前端按序拼接 answer 值即显示图片，content 格式与前端兼容零改动；
 * 落库保持原文，存量历史 row 读时自动修复，无需迁移。改写失败不可签发的引用原样保留。</p>
 */
@ApplicationScoped
public class ChatQueryService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    /** 对话图片引用网络地址化（端口，knowledge 实现）：历史 ASSISTANT 内容读取时现签改写 */
    private final ChatImageAssetUrlRewriter imageAssetUrlRewriter;
    private final MessageFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;

    public ChatQueryService(ConversationRepository conversationRepository,
                            ConversationMessageRepository messageRepository,
                            ChatImageAssetUrlRewriter imageAssetUrlRewriter,
                            MessageFeedbackRepository feedbackRepository,
                            ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.imageAssetUrlRewriter = imageAssetUrlRewriter;
        this.feedbackRepository = feedbackRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询账号下的活跃会话（created_at 降序）
     */
    @WithSession
    public Uni<PageResult<ConversationListItem>> listConversations(String account, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        // 标量 count 与实体 list 串行（Hibernate Reactive 并发实体 list 有 LoadContexts 风险）
        return conversationRepository.countActiveByAccount(account)
                .onItem().transformToUni(total ->
                        conversationRepository.findActiveByAccount(account, offset, pageSize)
                                .onItem().transform(list -> toPage(list, total, page, pageSize)));
    }

    /**
     * 分页查询会话消息（created_at 升序）
     *
     * @param conversationId 会话 ID
     * @param account        当前账号（归属校验）
     */
    @WithSession
    public Uni<PageResult<MessageListItem>> listMessages(String conversationId, String account,
                                                         int page, int pageSize, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        ConversationId id = ConversationId.of(conversationId);
        return conversationRepository.findById(id)
                .onItem().ifNull().failWith(() -> new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(entity -> {
                    if (!entity.getAccount().equals(account)) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    int offset = (page - 1) * pageSize;
                    return messageRepository.countByConversationId(id)
                            .onItem().transformToUni(total ->
                                    messageRepository.findByConversationId(id, offset, pageSize)
                                            .onItem().transformToUni(list ->
                                                    toMessagePage(list, total, page, pageSize, account)));
                });
    }

    private PageResult<ConversationListItem> toPage(List<com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity> entities,
                                                    long total, int page, int pageSize) {
        List<ConversationListItem> records = entities.stream()
                .map(e -> new ConversationListItem(
                        e.getId().value(), e.getTitle(), e.getStatus(), e.getPinned(),
                        e.getFileName(), e.getFileSize(), e.getFileType(),
                        e.getCreatedAt()))
                .toList();
        long totalPage = pageSize > 0 ? (total + pageSize - 1) / pageSize : 0;
        return new PageResult<>(page, pageSize, totalPage, total, records);
    }

    /**
     * 消息页组装（响应式）：ASSISTANT 内容逐条图片现签改写后组装，保序返回；
     * 再按页内 ASSISTANT 消息 id 一次批量回填当前用户反馈（不做 N+1）
     */
    private Uni<PageResult<MessageListItem>> toMessagePage(List<ConversationMessageEntity> entities,
                                                           long total, int page, int pageSize, String account) {
        List<Uni<MessageListItem>> itemUnis = entities.stream()
                .map(this::toMessageItem)
                .toList();
        return Uni.combine().all().unis(itemUnis)
                .with(items -> items.stream()
                        .filter(MessageListItem.class::isInstance)
                        .map(MessageListItem.class::cast)
                        .toList())
                .onItem().transformToUni(records -> withFeedback(records, account))
                .onItem().transform(records -> {
                    long totalPage = pageSize > 0 ? (total + pageSize - 1) / pageSize : 0;
                    return new PageResult<>(page, pageSize, totalPage, total, records);
                });
    }

    /**
     * 批量回填当前用户对页内 ASSISTANT 消息的反馈（一次查询；无 ASSISTANT 行则跳过）
     */
    private Uni<List<MessageListItem>> withFeedback(List<MessageListItem> records, String account) {
        List<String> assistantIds = records.stream()
                .filter(r -> ConversationMessageEntity.ROLE_ASSISTANT.equals(r.role()))
                .map(MessageListItem::messageId)
                .toList();
        if (assistantIds.isEmpty()) {
            return Uni.createFrom().item(records);
        }
        return feedbackRepository.findByMessageIdsAndUser(assistantIds, account)
                .onItem().transform(feedbacks -> {
                    Map<String, MessageFeedbackEntity> byMessageId = feedbacks.stream()
                            .collect(Collectors.toMap(MessageFeedbackEntity::getMessageId, f -> f));
                    return records.stream()
                            .map(item -> attachFeedback(item, byMessageId.get(item.messageId())))
                            .toList();
                });
    }

    /**
     * 将既有反馈覆盖到消息项（未反馈行为 null）
     */
    private MessageListItem attachFeedback(MessageListItem item, MessageFeedbackEntity feedback) {
        if (feedback == null) {
            return item;
        }
        return new MessageListItem(item.messageId(), item.role(), item.content(), item.createdAt(),
                item.searchKbEnabled(), item.searchExternalEnabled(), item.kbIds(),
                item.deepThinking(), item.docTypes(), item.version(),
                feedback.getRating(), feedback.getReasons(), feedback.getComment(), feedback.getUpdatedAt());
    }

    /**
     * 单条消息 → 列表项：ASSISTANT content（JSON 拼接）经 answer 值拼接域改写（失败降级原文，
     * 端口内部亦不抛异常）；USER 消息/空内容原样
     */
    private Uni<MessageListItem> toMessageItem(ConversationMessageEntity entity) {
        String content = entity.getContent();
        if (!ConversationMessageEntity.ROLE_ASSISTANT.equals(entity.getRole())
                || content == null || content.isEmpty()) {
            return Uni.createFrom().item(toItem(entity, content));
        }
        ChatAnswerImageRewriter imageRewriter = new ChatAnswerImageRewriter(imageAssetUrlRewriter, objectMapper);
        return imageRewriter.rewriteContent(content)
                .onFailure().recoverWithItem(content)
                .onItem().transform(rewritten -> toItem(entity, rewritten));
    }

    private MessageListItem toItem(ConversationMessageEntity entity, String content) {
        return new MessageListItem(entity.getId().value(), entity.getRole(), content, entity.getCreatedAt(),
                entity.getSearchKbEnabled(), entity.getSearchExternalEnabled(), entity.getKbIds(),
                entity.getDeepThinking(), entity.getDocTypes(), entity.getVersion(),
                null, null, null, null);
    }
}
