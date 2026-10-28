package com.hisilicon.knowledge.platform.llm.command;

import com.hisilicon.knowledge.platform.commons.ai.api.ModelServiceClient;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationFile;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.domain.service.ChatFileStorageService;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 创建会话命令服务
 * <p>创建会话（可选绑定单个问答文件）。文件校验：扩展名白名单（与知识库文档上传一致，
 * 不支持压缩包）、≤10MB（kb.upload.chat-max-file-size 配置）。</p>
 */
@ApplicationScoped
public class CreateConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(CreateConversationCommandService.class);

    private final long maxFileSize;
    private final ConversationRepository conversationRepository;
    private final ChatFileStorageService fileStorageService;
    private final ModelServiceClient modelServiceClient;

    public CreateConversationCommandService(
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
     * 创建会话
     *
     * <p>搜索范围/芯片/深度思考为消息级配置（POST /chat/messages/send 传入），不在建会话时设定。</p>
     *
     * @param command 创建会话命令参数（账号/标题/文件三元组/语言环境）
     * @return 创建后的会话实体
     */
    @WithTransaction
    public Uni<ConversationEntity> create(CreateConversationCommand command) {
        String fileName = command.fileName();
        ConversationEntity entity = ConversationEntity.create(command.account(), command.title());
        if (fileName == null || fileName.isBlank()) {
            return conversationRepository.persist(entity);
        }
        // 有文件：先校验（大小/类型），再上传模型服务拿临时库 ID，再存储 + 绑定
        ChatFileValidator.validate(fileName, command.content(), maxFileSize, command.locale());
        AppMessages appMessages = LocaleUtil.getAppMessage(command.locale());
        return modelServiceClient.uploadTempDocs(fileName, command.content())
                // 模型服务不通/异常（连不上/5xx/超时/空响应）→ C0001（调用第三方服务出错），
                // 不走 IOExceptionMapper 的裸 500
                .onFailure().transform(t -> new ModelServiceException(appMessages.modelServiceError(), t))
                .onItem().transformToUni(kbId -> {
                    String fileType = ChatFileValidator.deduceFileType(fileName);
                    String storagePath = "kb/chat/" + entity.getId().value() + "/" + fileName;
                    return fileStorageService.store(storagePath, command.content())
                            .onItem().transformToUni(actualPath ->
                                    conversationRepository.persist(
                                            entity.attachFile(
                                                    new ConversationFile(fileName, (long) command.content().length,
                                                            actualPath, command.mimeType(), fileType),
                                                    kbId)));
                });
    }
}