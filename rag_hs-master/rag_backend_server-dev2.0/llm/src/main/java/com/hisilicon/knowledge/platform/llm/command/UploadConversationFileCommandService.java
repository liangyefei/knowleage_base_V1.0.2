package com.hisilicon.knowledge.platform.llm.command;

import com.hisilicon.knowledge.platform.commons.ai.api.ModelServiceClient;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationFile;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.domain.service.ChatFileStorageService;
import com.hisilicon.knowledge.platform.llm.exception.ConversationFileAlreadyExistsException;
import com.hisilicon.knowledge.platform.llm.exception.ConversationNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话中上传文件命令服务
 * <p>多轮会话中补传文件（创建时未带文件）。已绑定文件（file_path 非空）→ 重复上传报错。</p>
 */
@ApplicationScoped
public class UploadConversationFileCommandService {

    private static final Logger logger = LoggerFactory.getLogger(UploadConversationFileCommandService.class);

    private final long maxFileSize;
    private final ConversationRepository conversationRepository;
    private final ChatFileStorageService fileStorageService;
    private final ModelServiceClient modelServiceClient;

    public UploadConversationFileCommandService(
            @ConfigProperty(name = "kb.upload.chat-max-file-size", defaultValue = "10M")
            MemorySize maxFileSize,
            ConversationRepository conversationRepository,
            ChatFileStorageService fileStorageService,
            ModelServiceClient modelServiceClient) {
        this.maxFileSize = maxFileSize.asLongValue();
        this.conversationRepository = conversationRepository;
        this.fileStorageService = fileStorageService;
        this.modelServiceClient = modelServiceClient;
    }

    /**
     * 会话中上传文件
     *
     * @param command 上传命令参数（会话 ID/账号/文件三元组/语言环境）
     * @return 更新后的会话实体
     */
    @WithTransaction
    public Uni<ConversationEntity> upload(UploadConversationFileCommand command) {
        AppMessages appMessages = LocaleUtil.getAppMessage(command.locale());
        String account = command.account();
        String fileName = command.fileName();
        byte[] content = command.content();
        String locale = command.locale();
        return conversationRepository.findById(ConversationId.of(command.conversationId()))
                .onItem().ifNull().failWith(() -> new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(entity -> {
                    // 归属校验
                    if (!entity.getAccount().equals(account)) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    // 状态校验：仅 ACTIVE 可补传
                    if (!ConversationEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    // 重复上传校验：已有文件 → 报错
                    if (entity.getFilePath() != null && !entity.getFilePath().isBlank()) {
                        return Uni.createFrom().failure(new ConversationFileAlreadyExistsException(appMessages.conversationFileExists()));
                    }
                    // 文件校验
                    ChatFileValidator.validate(fileName, content, maxFileSize, locale);
                    return modelServiceClient.uploadTempDocs(fileName, content)
                            // 模型服务不通/异常 → C0001（调用第三方服务出错），不走 IOExceptionMapper 的 500
                            .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                            .onItem().transformToUni(kbId -> {
                                String fileType = ChatFileValidator.deduceFileType(fileName);
                                String storagePath = "kb/chat/" + entity.getId().value() + "/" + fileName;
                                return fileStorageService.store(storagePath, content)
                                        .onItem().transformToUni(actualPath -> {
                                            ConversationEntity updated = entity.attachFile(
                                                    new ConversationFile(fileName, (long) content.length,
                                                            actualPath, command.mimeType(), fileType),
                                                    kbId);
                                            return conversationRepository.update(updated);
                                        });
                            });
                });
    }
}