package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.llm.infrastructure.service.ChatLocalFileStorageService;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link ChatLocalFileStorageService}
 */
public class MockChatFileStorageService extends ChatLocalFileStorageService {

    /** 最近一次 store 的相对路径 */
    private static volatile String storedPath;
    /** 最近一次 moveToTrash 的路径 */
    private static volatile String trashedPath;

    public MockChatFileStorageService() {
        super("mock-storage", null);
    }

    public static String getStoredPath() {
        return storedPath;
    }

    public static String getTrashedPath() {
        return trashedPath;
    }

    public static void reset() {
        storedPath = null;
        trashedPath = null;
    }

    @Override
    public Uni<String> store(String relativePath, byte[] content) {
        storedPath = relativePath;
        return Uni.createFrom().item(relativePath);
    }

    @Override
    public Uni<Void> moveToTrash(String path) {
        trashedPath = path;
        return Uni.createFrom().voidItem();
    }
}
