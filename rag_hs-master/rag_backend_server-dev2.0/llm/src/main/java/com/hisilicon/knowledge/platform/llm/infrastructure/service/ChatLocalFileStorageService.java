package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.hisilicon.knowledge.platform.llm.domain.service.ChatFileStorageService;
import com.hisilicon.knowledge.platform.llm.exception.ConversationFileStorageException;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 对话文件本地存储实现
 * <p>文件存到配置的本地目录（与知识库文件存储同根，前缀 kb/chat/）。
 * 磁盘 IO 走 {@code vertx.executeBlocking} worker 线程池，不阻塞 event loop。</p>
 */
@ApplicationScoped
public class ChatLocalFileStorageService implements ChatFileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(ChatLocalFileStorageService.class);

    private final String localPath;
    private final Vertx vertx;

    public ChatLocalFileStorageService(
            @ConfigProperty(name = "kb.storage.local-path", defaultValue = "/data/kb-files")
            String localPath,
            Vertx vertx) {
        this.localPath = localPath;
        this.vertx = vertx;
    }

    @Override
    public Uni<String> store(String relativePath, byte[] content) {
        return vertx.executeBlocking(() -> {
            try {
                Path fullPath = Paths.get(localPath, relativePath);
                Path parent = fullPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(fullPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                logger.info("Conversation file stored: path={}, size={}bytes", fullPath, content.length);
                return relativePath;
            } catch (IOException e) {
                logger.error("Failed to store conversation file: path={}, error={}", relativePath, e.getMessage());
                throw new ConversationFileStorageException("Failed to store conversation file: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Uni<Void> moveToTrash(String path) {
        return vertx.<Void>executeBlocking(() -> {
            try {
                Path source = Paths.get(localPath, path);
                if (!Files.exists(source)) {
                    logger.info("Conversation file no longer exists, skipping move to trash: path={}", path);
                    return null;
                }
                Path trashTarget = Paths.get(localPath, ".trash", path);
                Files.createDirectories(trashTarget.getParent());
                Files.move(source, trashTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                logger.info("Conversation file moved to trash: from={}, to={}", source, trashTarget);
                return null;
            } catch (IOException e) {
                logger.error("Failed to move conversation file to trash (file left in place): path={}, error={}", path, e.getMessage());
                throw new ConversationFileStorageException(
                        "Failed to move conversation file to trash: " + e.getMessage(), e);
            }
        });
    }
}
