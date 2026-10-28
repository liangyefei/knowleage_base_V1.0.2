package com.hisilicon.knowledge.platform.knowledge.domain.service;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileRecycleService} 身份校验矩阵单测：
 * 同名重传覆盖/新建的文件必须跳过回收；身份未变的正常回收；缺失文件幂等。
 */
class FileRecycleServiceTest {

    /** 进程内假 FS：stat/moveToTrash 可控，记录移动目标与空目录清理请求 */
    private static class FakeFileStorageService implements FileStorageService {
        final Map<String, FileStorageService.FileSnapshot> files = new HashMap<>();
        final Set<String> trashed = new HashSet<>();
        final Set<String> emptyDirCleanupCalls = new HashSet<>();

        void put(String path, long size, String mtime) {
            files.put(path, new FileStorageService.FileSnapshot(size, Instant.parse(mtime)));
        }

        void remove(String path) {
            files.remove(path);
        }

        @Override
        public Uni<FileStorageService.FileSnapshot> stat(String path) {
            return Uni.createFrom().item(files.get(path));
        }

        @Override
        public Uni<Void> moveToTrash(String path) {
            trashed.add(path);
            files.remove(path);
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Integer> deleteEmptyDirs(java.util.Collection<String> dirs) {
            emptyDirCleanupCalls.addAll(dirs);
            return Uni.createFrom().item(0);
        }

        // ===== 以下与本测试无关，no-op =====
        @Override public Uni<String> store(String path, byte[] content) { return Uni.createFrom().item(path); }
        @Override public String storeBlocking(String path, byte[] content) { return path; }
        @Override public Uni<Void> ensureDirectory(String relativePath) { return Uni.createFrom().voidItem(); }
        @Override public Uni<byte[]> read(String path) { return Uni.createFrom().item(new byte[0]); }
        @Override public Uni<java.nio.file.Path> resolveReadableFile(String path) { return Uni.createFrom().nullItem(); }
        @Override public Uni<java.io.InputStream> openInputStream(String path) { return Uni.createFrom().nullItem(); }
        @Override public Uni<java.nio.channels.SeekableByteChannel> openReadChannel(String path) { return Uni.createFrom().nullItem(); }
        @Override public java.io.InputStream openInputStreamBlocking(String path) { return java.io.InputStream.nullInputStream(); }
        @Override public Uni<Integer> cleanTrash(int retentionDays) { return Uni.createFrom().item(0); }
        @Override public Uni<Long> appendChunk(String tempPath, long offset, byte[] chunk) { return Uni.createFrom().item(0L); }
        @Override public Uni<Long> size(String path) { return Uni.createFrom().item(0L); }
        @Override public Uni<Void> move(String fromPath, String toPath) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Boolean> exists(String relativePath) { return Uni.createFrom().item(false); }
        @Override public Uni<java.util.Set<String>> existingRegularFilesWithin(String relativeDir, java.util.Collection<String> relativePaths) { return Uni.createFrom().item(java.util.Set.of()); }
        @Override public Uni<Boolean> hasRecentWrites(String relativeDir, java.time.LocalDateTime since) { return Uni.createFrom().item(false); }
    }

    private final FakeFileStorageService fs = new FakeFileStorageService();
    private final FileRecycleService svc = new FileRecycleService(fs);

    @Test
    void shouldMoveWhenIdentityUnchanged() {
        fs.put("kb/kb-1/测试.pdf", 100L, "2026-08-27T00:00:00Z");
        Map<String, FileStorageService.FileSnapshot> snapshots =
                svc.snapshot(List.of("kb/kb-1/测试.pdf")).await().indefinitely();

        Integer moved = svc.recycleGuarded("test", snapshots).await().indefinitely();

        assertEquals(1, moved);
        assertTrue(fs.trashed.contains("kb/kb-1/测试.pdf"));
        assertTrue(fs.emptyDirCleanupCalls.contains("kb/kb-1"), "回收后应请求清理父目录链");
    }

    @Test
    void shouldSkipWhenFileOverwrittenByReupload() {
        fs.put("kb/kb-1/测试.pdf", 100L, "2026-08-27T00:00:00Z");
        Map<String, FileStorageService.FileSnapshot> snapshots =
                svc.snapshot(List.of("kb/kb-1/测试.pdf")).await().indefinitely();

        // 删除提交后同名重传覆盖（内容不同 → mtime/size 变化）
        fs.put("kb/kb-1/测试.pdf", 250L, "2026-08-27T12:00:00Z");

        Integer moved = svc.recycleGuarded("test", snapshots).await().indefinitely();

        assertEquals(0, moved, "重传覆盖的新文件不得被移入回收站");
        assertTrue(fs.files.containsKey("kb/kb-1/测试.pdf"), "新文件应留在原地");
    }

    @Test
    void shouldSkipWhenFileAppearsAfterSnapshot() {
        // 收集路径时文件不存在（快照 null），回收时却出现了 → 删除后新建的文件，不得回收
        Map<String, FileStorageService.FileSnapshot> snapshots =
                svc.snapshot(List.of("kb/kb-1/新建.pdf")).await().indefinitely();
        fs.put("kb/kb-1/新建.pdf", 50L, "2026-08-27T13:00:00Z");

        Integer moved = svc.recycleGuarded("test", snapshots).await().indefinitely();

        assertEquals(0, moved);
        assertTrue(fs.files.containsKey("kb/kb-1/新建.pdf"));
    }

    @Test
    void shouldBeIdempotentWhenFileAlreadyGone() {
        fs.put("kb/kb-1/旧.pdf", 10L, "2026-08-27T00:00:00Z");
        Map<String, FileStorageService.FileSnapshot> snapshots =
                svc.snapshot(List.of("kb/kb-1/旧.pdf")).await().indefinitely();
        fs.remove("kb/kb-1/旧.pdf");

        Integer moved = svc.recycleGuarded("test", snapshots).await().indefinitely();

        assertEquals(0, moved, "文件已不在原地，无实际移动");
    }
}
