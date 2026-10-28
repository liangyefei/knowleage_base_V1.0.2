package com.hisilicon.knowledge.platform.llm.command;

import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationMessageRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.domain.service.ChatFileStorageService;
import com.hisilicon.knowledge.platform.llm.exception.ConversationNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 删除会话命令服务
 * <p>物理删除：会话 + 全部消息 + 文件同步删除（两阶段：DB 删除事务 + 提交后文件移入回收站，
 * 与文档删除 F-057 同模式）。已归档也可删；不存在视同不存在。</p>
 */
@ApplicationScoped
public class DeleteConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(DeleteConversationCommandService.class);

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ChatFileStorageService fileStorageService;

    /**
     * 自注入：deleteInTx（@WithTransaction）的 CDI 代理桥接
     */
    @Inject
    DeleteConversationCommandService self;

    public DeleteConversationCommandService(ConversationRepository conversationRepository,
                                            ConversationMessageRepository messageRepository,
                                            ChatFileStorageService fileStorageService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 删除会话（外层只读校验 + 事务编排）
     *
     * @param conversationId 会话 ID
     * @param account        当前账号（归属校验）
     * @return 完成信号
     */
    @WithSession
    public Uni<Void> delete(String conversationId, String account, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        ConversationId id = ConversationId.of(conversationId);
        return conversationRepository.findById(id)
                .onItem().ifNull().failWith(() -> new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(entity -> {
                    if (!entity.getAccount().equals(account)) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    String filePath = entity.getFilePath();
                    return self.deleteInTx(id)
                            // 提交后文件移入回收站（失败仅日志，不影响业务）
                            .onItem().call(() -> {
                                if (filePath == null || filePath.isBlank()) {
                                    return Uni.createFrom().voidItem();
                                }
                                return fileStorageService.moveToTrash(filePath)
                                        .onFailure().recoverWithItem(t -> {
                                            logger.error("Failed to move file to trash after conversation deletion (cleanup task will retry): path={}, error={}",
                                                    filePath, t.getMessage());
                                            return null;
                                        });
                            });
                });
    }

    /**
     * 删除事务：消息 + 会话物理删除
     */
    @WithTransaction
    Uni<Void> deleteInTx(ConversationId id) {
        return messageRepository.deleteByConversationId(id)
                .onItem().transformToUni(v -> conversationRepository.delete(id));
    }
}
