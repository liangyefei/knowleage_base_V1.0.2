package com.hisilicon.knowledge.platform.llm.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.FeedbackContent;
import com.hisilicon.knowledge.platform.llm.domain.entity.FeedbackRating;
import com.hisilicon.knowledge.platform.llm.domain.entity.FeedbackReason;
import com.hisilicon.knowledge.platform.llm.domain.entity.MessageFeedbackEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationMessageRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.MessageFeedbackRepository;
import com.hisilicon.knowledge.platform.llm.exception.ConversationNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提交回答反馈命令服务
 * <p>对 ASSISTANT 回答提交"有帮助/没帮助"反馈。一消息一用户一行，重复提交 UPSERT 覆盖保留最新态。
 * 服务端兜底：HELPFUL 强制置空 reasons/comment（不信前端）；消息不存在/非 ASSISTANT/越权
 * 统一视同不存在（A0430），与 pin/历史查询语义一致。</p>
 */
@ApplicationScoped
public class FeedbackMessageCommandService {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackMessageCommandService.class);

    private final ConversationMessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final MessageFeedbackRepository feedbackRepository;
    private final com.hisilicon.knowledge.platform.llm.infrastructure.repository.QaInsightPanacheRepository insightRepo;

    public FeedbackMessageCommandService(ConversationMessageRepository messageRepository,
                                         ConversationRepository conversationRepository,
                                         MessageFeedbackRepository feedbackRepository,
                                         com.hisilicon.knowledge.platform.llm.infrastructure.repository.QaInsightPanacheRepository insightRepo) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.feedbackRepository = feedbackRepository;
        this.insightRepo = insightRepo;
    }

    /**
     * 提交反馈（首次插入 / 覆盖更新共用）
     *
     * @param command 反馈命令
     * @param account 当前账号（归属校验）
     * @param locale  语言
     */
    @WithTransaction
    public Uni<Void> feedback(FeedbackMessageCommand command, String account, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        if (!FeedbackRating.isValid(command.rating())) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0400, appMessages.feedbackRatingInvalid()));
        }
        // 规范化原因与描述（服务端兜底，不信前端输入）
        String rating = command.rating();
        String reasons = null;
        String comment = null;
        if (FeedbackRating.UNHELPFUL.equals(rating)) {
            java.util.Optional<String> normalized = FeedbackReason.normalize(command.reasons());
            if (normalized.isEmpty()) {
                return Uni.createFrom().failure(new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0400, appMessages.feedbackReasonsInvalid()));
            }
            reasons = normalized.get();
            comment = command.comment() != null && !command.comment().isBlank()
                    ? command.comment().trim() : null;
        }
        String normalizedReasons = reasons;
        String normalizedComment = comment;
        return messageRepository.findById(command.messageId())
                .onItem().ifNull().failWith(() -> new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0430, appMessages.messageNotFound()))
                .onItem().transformToUni(message -> {
                    if (!ConversationMessageEntity.ROLE_ASSISTANT.equals(message.getRole())) {
                        // 仅 ASSISTANT 回答可反馈，其余视同不存在
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0430, appMessages.messageNotFound()));
                    }
                    return checkConversation(message, account, appMessages)
                            .onItem().transformToUni(ignored -> upsert(
                                    message, account, rating, normalizedReasons, normalizedComment));
                });
    }

    /**
     * 会话归属校验：不存在/非本人/已归档 → 视同不存在（与 pin 语义一致）
     */
    private Uni<Void> checkConversation(ConversationMessageEntity message, String account,
                                        AppMessages appMessages) {
        return conversationRepository.findById(message.getConversationId())
                .onItem().ifNull().failWith(() ->
                        new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(conversation -> {
                    if (!conversation.getAccount().equals(account)
                            || !ConversationEntity.STATUS_ACTIVE.equals(conversation.getStatus())) {
                        return Uni.createFrom().failure(
                                new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * UPSERT：命中既有反馈则覆盖为最新态（保留首次 createdAt），否则插入新行
     */
    private Uni<Void> upsert(ConversationMessageEntity message, String account, String rating,
                             String reasons, String comment) {
        String messageId = message.getId().value();
        String conversationId = message.getConversationId().value();
        return feedbackRepository.findByMessageAndUser(messageId, account)
                .onItem().transformToUni(existing -> {
                    MessageFeedbackEntity entity = existing != null
                            ? MessageFeedbackEntity.update(existing, rating, reasons, comment)
                            : MessageFeedbackEntity.create(messageId, conversationId, account,
                                    new FeedbackContent(rating, reasons, comment));
                    return feedbackRepository.persist(entity).replaceWithVoid();
                })
                .onItem().transformToUni(v -> syncInsightRating(messageId, rating))
                .onItem().invoke(v -> logger.info("Answer feedback submitted: messageId={}, account={}, rating={}",
                        messageId, account, rating));
    }

    /**
     * 同步用户评价到 qa_insights（赞/踩）；查不到不报错。
     * <p>按 message_id 关联查（qa_insights.message_id = ASSISTANT 消息 id，uk 唯一）。</p>
     */
    private Uni<Void> syncInsightRating(String messageId, String rating) {
        return insightRepo.find("messageId", messageId).firstResult()
                .onItem().ifNotNull().transformToUni(po -> {
                    po.setUserRating("HELPFUL".equals(rating) ? "LIKE" : "DISLIKE");
                    po.setUpdatedAt(java.time.LocalDateTime.now());
                    return insightRepo.persist(po).replaceWithVoid();
                })
                .onItem().ifNull().continueWith(() -> null)
                .replaceWithVoid()
                .onFailure().recoverWithUni(e -> {
                    logger.warn("Sync insight rating failed: messageId={}, err={}", messageId, e.getMessage());
                    return Uni.createFrom().voidItem();
                });
    }
}
