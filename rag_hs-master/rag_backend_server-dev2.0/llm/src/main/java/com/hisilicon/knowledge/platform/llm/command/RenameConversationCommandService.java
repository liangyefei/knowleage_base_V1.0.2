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
 * 重命名会话命令服务
 * <p>手动设置标题并标记 titleManuallySet=true——之后首条消息自动生成标题不再覆盖手动标题。
 * 会话不存在/越权/已归档 → 视同不存在（A0430）。</p>
 */
@ApplicationScoped
public class RenameConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(RenameConversationCommandService.class);

    private final ConversationRepository conversationRepository;

    public RenameConversationCommandService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    /**
     * 重命名会话标题
     *
     * @param conversationId 会话 ID
     * @param account        当前账号（归属校验）
     * @param newTitle       新标题
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> rename(String conversationId, String account, String newTitle, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        ConversationId id = ConversationId.of(conversationId);
        return conversationRepository.findById(id)
                .onItem().ifNull().failWith(() -> new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(entity -> {
                    if (!entity.getAccount().equals(account)) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    if (!ConversationEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        // 已归档重命名 → 视同不存在（幂等保护）
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    return conversationRepository.update(entity.rename(newTitle))
                            .replaceWithVoid();
                });
    }
}
