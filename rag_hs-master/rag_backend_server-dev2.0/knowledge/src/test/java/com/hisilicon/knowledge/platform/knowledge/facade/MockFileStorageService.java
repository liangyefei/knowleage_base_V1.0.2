package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.LocalFileStorageService;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link LocalFileStorageService}
 * <p>store 捕获路径与内容并原样返回（不触达磁盘/vertx，避免 uniquePath 重名序号导致断言抖动）；
 * read 返回预设字节（按路径匹配）；moveToTrash/cleanTrash 幂等 no-op。避免单测触碰真实文件系统。</p>
 */
public class MockFileStorageService extends LocalFileStorageService {

    /** read 返回的内容（按 path 精确匹配；null = 读取失败） */
    private static volatile byte[] readContent;

    /** 最近一次 read 的路径 */
    private static volatile String lastReadPath;

    /** 是否模拟读取失败 */
    private static volatile boolean failRead;

    /** 最近一次 store 的相对路径 */
    private static volatile String lastStorePath;

    /** 最近一次 store 的内容 */
    private static volatile byte[] lastStoreContent;

    /** 最近一次 moveToTrash 的路径 */
    private static volatile String lastTrashPath;

    /** 最近一次 ensureDirectory 的路径 */
    private static volatile String lastEnsureDirPath;

    /** 模拟磁盘现存路径集合（exists/move 用） */
    private static volatile java.util.Set<String> existingPaths = java.util.Set.of();

    /** 最近一次 move 的源路径 */
    private static volatile String lastMoveFrom;

    /** 最近一次 move 的目标路径 */
    private static volatile String lastMoveTo;

    public MockFileStorageService() {
        // 全方法 override，不触达 vertx 字段（其余方法未 override 时 NPE 会暴露意外调用）
        super("mock-storage-root", null);
    }

    public static void reset() {
        readContent = null;
        lastReadPath = null;
        failRead = false;
        lastStorePath = null;
        lastStoreContent = null;
        lastTrashPath = null;
        lastEnsureDirPath = null;
        existingPaths = java.util.Set.of();
        lastMoveFrom = null;
        lastMoveTo = null;
    }

    public static void setReadContent(byte[] content) {
        readContent = content;
    }

    public static String getLastReadPath() {
        return lastReadPath;
    }

    public static void setFailRead(boolean fail) {
        failRead = fail;
    }

    public static String getLastStorePath() {
        return lastStorePath;
    }

    public static byte[] getLastStoreContent() {
        return lastStoreContent;
    }

    public static String getLastTrashPath() {
        return lastTrashPath;
    }

    public static String getLastEnsureDirPath() {
        return lastEnsureDirPath;
    }

    /** 预置磁盘现存路径（exists 返回 true 的集合） */
    public static void setExistingPaths(java.util.Collection<String> paths) {
        existingPaths = new java.util.HashSet<>(paths);
    }

    public static String getLastMoveFrom() {
        return lastMoveFrom;
    }

    public static String getLastMoveTo() {
        return lastMoveTo;
    }

    @Override
    public Uni<Boolean> exists(String relativePath) {
        return Uni.createFrom().item(existingPaths.contains(relativePath));
    }

    @Override
    public Uni<Void> move(String fromPath, String toPath) {
        lastMoveFrom = fromPath;
        lastMoveTo = toPath;
        // 模拟 move 语义：源从磁盘消失、目标出现
        java.util.Set<String> next = new java.util.HashSet<>(existingPaths);
        next.remove(fromPath);
        next.add(toPath);
        existingPaths = next;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<String> store(String relativePath, byte[] content) {
        lastStorePath = relativePath;
        lastStoreContent = content;
        return Uni.createFrom().item(relativePath);
    }

    @Override
    public Uni<Void> ensureDirectory(String relativePath) {
        lastEnsureDirPath = relativePath;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> moveToTrash(String path) {
        lastTrashPath = path;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<FileStorageService.FileSnapshot> stat(String path) {
        // 单测环境视为文件不存在（recycleGuarded 走幂等跳过分支，不触达磁盘/vertx）
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<Integer> deleteEmptyDirs(java.util.Collection<String> dirs) {
        // 单测 no-op（不触达磁盘/vertx）
        return Uni.createFrom().item(0);
    }

    @Override
    public Uni<Integer> cleanTrash(int retentionDays) {
        return Uni.createFrom().item(0);
    }

    @Override
    public Uni<byte[]> read(String path) {
        lastReadPath = path;
        if (failRead) {
            return Uni.createFrom().failure(new RuntimeException("模拟文件读取失败"));
        }
        return Uni.createFrom().item(readContent);
    }

    /**
     * 流式解析（download/file-content 非 md 路径）：把预设内容物化为临时文件并返回其路径，
     * 记录 lastReadPath 与 read 同口径（路径断言复用）。failRead 同样模拟失败。
     */
    @Override
    public Uni<java.nio.file.Path> resolveReadableFile(String path) {
        lastReadPath = path;
        if (failRead) {
            return Uni.createFrom().failure(new RuntimeException("模拟文件不存在"));
        }
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("mock-stream", ".bin");
            java.nio.file.Files.write(tmp, readContent == null ? new byte[0] : readContent);
            tmp.toFile().deleteOnExit();
            return Uni.createFrom().item(tmp);
        } catch (java.io.IOException e) {
            return Uni.createFrom().failure(new RuntimeException("临时文件物化失败", e));
        }
    }

    /**
     * 兜底判存（对话图片资产接口 / 改写器）：按"预置现存路径 + 放行目录段级包含"模拟，
     * 不触达磁盘与 vertx；真实实现另做软链真实路径复核，见 {@code LocalFileStorageService}。
     */
    @Override
    public Uni<java.util.Set<String>> existingRegularFilesWithin(
            String relativeDir, java.util.Collection<String> relativePaths) {
        java.util.Set<String> found = new java.util.HashSet<>();
        if (relativeDir == null || relativeDir.isBlank() || relativePaths == null) {
            return Uni.createFrom().item(found);
        }
        for (String path : relativePaths) {
            if (path != null && existingPaths.contains(path) && path.startsWith(relativeDir + "/")) {
                found.add(path);
            }
        }
        return Uni.createFrom().item(found);
    }
}
