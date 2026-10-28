package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalFileStorageService 回收站逻辑单元测试（纯 JUnit，无 Quarkus 上下文）
 * <p>覆盖两阶段删除：moveToTrash 幂等/目录结构、cleanTrash 超期清理/保留期内保留。</p>
 */
class LocalFileStorageServiceTrashTest {

    @TempDir
    Path tempDir;

    /** 共享 Vertx 实例（executeBlocking 需要 event loop，测试结束关闭） */
    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close().await().indefinitely();
    }

    private LocalFileStorageService newService() {
        return new LocalFileStorageService(tempDir.toString(), VERTX);
    }

    @Test
    void shouldMoveToTrashPreservingRelativeStructure() {
        LocalFileStorageService service = newService();
        String rel = "kb-1/cat-1/文档.txt";
        Path source = tempDir.resolve(rel);
        try {
            Files.createDirectories(source.getParent());
            Files.writeString(source, "content");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        service.moveToTrash(rel).await().indefinitely();

        // 原位置消失，trash 目录保留相对结构
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(tempDir.resolve(".trash").resolve(rel)));
    }

    @Test
    void shouldBeIdempotentWhenFileMissing() {
        LocalFileStorageService service = newService();

        service.moveToTrash("kb-1/不存在.txt").await().indefinitely();

        // 文件不存在视为成功（幂等，二次删除/并发删除安全）
        assertFalse(Files.exists(tempDir.resolve(".trash").resolve("kb-1/不存在.txt")));
    }

    @Test
    void shouldCleanOnlyExpiredFiles() {
        LocalFileStorageService service = newService();
        String oldRel = "kb-1/old.txt";
        String freshRel = "kb-1/fresh.txt";
        try {
            for (String rel : new String[]{oldRel, freshRel}) {
                Path p = tempDir.resolve(rel);
                Files.createDirectories(p.getParent());
                Files.writeString(p, "x");
            }
            // old 文件"原 mtime"设为 30 天前（模拟长期未修改的文件被删除）
            Files.setLastModifiedTime(tempDir.resolve(oldRel),
                    FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // 移入回收站：moveToTrash 将回收站内 mtime 归一化为移入时刻——
        // 保留期按"在回收站中的时长"起算，长期未修改的文件不会刚删除当晚就被物理清除
        service.moveToTrash(oldRel).await().indefinitely();
        service.moveToTrash(freshRel).await().indefinitely();

        assertEquals(0, service.cleanTrash(7).await().indefinitely());
        assertTrue(Files.exists(tempDir.resolve(".trash").resolve(oldRel)), "原 mtime 陈旧不应影响保留期");
        assertTrue(Files.exists(tempDir.resolve(".trash").resolve(freshRel)));

        // 回收站内的 old 副本滞留超过保留期后，仅它被清理
        try {
            Files.setLastModifiedTime(tempDir.resolve(".trash").resolve(oldRel),
                    FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(1, service.cleanTrash(7).await().indefinitely());
        assertFalse(Files.exists(tempDir.resolve(".trash").resolve(oldRel)));
        assertTrue(Files.exists(tempDir.resolve(".trash").resolve(freshRel)));
    }

    @Test
    void shouldReturnZeroWhenNoTrashDir() {
        LocalFileStorageService service = newService();

        int deleted = service.cleanTrash(7).await().indefinitely();

        assertEquals(0, deleted);
    }
}
