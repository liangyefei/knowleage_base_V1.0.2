package com.hisilicon.knowledge.platform.llm.command;

import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.exception.ConversationNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 置顶 / 取消置顶会话命令服务
 * <p>{@link #pin} 置顶（pinned=true），{@link #unpin} 取消置顶（pinned=false）。
 * 两接口单一职责，重复 pin/unpin 幂等。会话不存在/越权/已归档 → 视同不存在（A0430）。</p>
 */
@ApplicationScoped
public class PinConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(PinConversationCommandService.class);

    private final ConversationRepository conversationRepository;

    public PinConversationCommandService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    /**
     * 置顶会话
     *
     * @param conversationId 会话 ID
     * @param account         当前账号（归属校验）
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> pin(String conversationId, String account, String locale) {
        return applyPin(conversationId, account, true, locale);
    }

    /**
     * 取消置顶会话
     *
     * @param conversationId 会话 ID
     * @param account        当前账号（归属校验）
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> unpin(String conversationId, String account, String locale) {
        return applyPin(conversationId, account, false, locale);
    }

    /**
     * 置顶/取消置顶共用逻辑（由 {@link #pin}/{@link #unpin} 在各自事务内调用）
     */
    private Uni<Void> applyPin(String conversationId, String account, boolean pinned, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        ConversationId id = ConversationId.of(conversationId);
        return conversationRepository.findById(id)
                .onItem().ifNull().failWith(() -> new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(entity -> {
                    if (!entity.getAccount().equals(account)) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    if (!ConversationEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        // 已归档置顶/取消 → 视同不存在（幂等保护）
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    return conversationRepository.update(entity.pin(pinned))
                            .replaceWithVoid();
                });
    }
}
