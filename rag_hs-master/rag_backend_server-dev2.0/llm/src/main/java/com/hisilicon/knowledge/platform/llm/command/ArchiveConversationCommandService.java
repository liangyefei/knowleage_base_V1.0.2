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
 * 归档会话命令服务
 * <p>ACTIVE → ARCHIVED（软删除），文件保留在 SFS。已归档再次归档视同不存在。</p>
 */
@ApplicationScoped
public class ArchiveConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveConversationCommandService.class);

    private final ConversationRepository conversationRepository;

    public ArchiveConversationCommandService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    /**
     * 归档会话
     *
     * @param conversationId 会话 ID
     * @param account        当前账号（归属校验）
     * @return 完成信号
     */
    @WithTransaction
    public Uni<Void> archive(String conversationId, String account, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        ConversationId id = ConversationId.of(conversationId);
        return conversationRepository.findById(id)
                .onItem().ifNull().failWith(() -> new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(entity -> {
                    if (!entity.getAccount().equals(account)) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    if (!ConversationEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        // 已归档再次归档 → 视同不存在（幂等保护）
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    return conversationRepository.update(entity.archive())
                            .replaceWithVoid();
                });
    }
}
