package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalFileStorageService 分片/移动逻辑单元测试（纯 JUnit，无 Quarkus 上下文）
 * <p>覆盖 tus 续传地基：appendChunk 从 offset 定位覆盖写、size 查断点、move 临时→最终。</p>
 */
class LocalFileStorageServiceChunkTest {

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
    void shouldAppendChunkAtOffsetOverwritingHalfWrittenTail() {
        LocalFileStorageService service = newService();
        String rel = ".uploads/sess-1";

        // 首片：offset=0 写 "hello"
        long size1 = service.appendChunk(rel, 0, "hello".getBytes(StandardCharsets.UTF_8)).await().indefinitely();
        assertEquals(5, size1);
        assertContent(rel, "hello".getBytes(StandardCharsets.UTF_8));

        // 续片：offset=5 写 "-world"
        long size2 = service.appendChunk(rel, 5, "-world".getBytes(StandardCharsets.UTF_8)).await().indefinitely();
        assertEquals(11, size2);
        assertContent(rel, "hello-world".getBytes(StandardCharsets.UTF_8));

        // 断点续传覆盖半成品片：回到 offset=5 覆盖写 "WORLD!"（6 字节，覆盖 "-world"）
        long size3 = service.appendChunk(rel, 5, "WORLD!".getBytes(StandardCharsets.UTF_8)).await().indefinitely();
        assertEquals(11, size3);
        assertContent(rel, "helloWORLD!".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldCreateParentDirOnFirstChunk() {
        LocalFileStorageService service = newService();
        String rel = ".uploads/sess-2/deep.bin";

        service.appendChunk(rel, 0, new byte[]{1, 2, 3}).await().indefinitely();

        assertTrue(Files.exists(tempDir.resolve(rel)));
    }

    @Test
    void shouldReturnZeroWhenFileMissing() {
        LocalFileStorageService service = newService();

        long size = service.size(".uploads/不存在").await().indefinitely();

        assertEquals(0L, size);
    }

    @Test
    void shouldReturnActualSizeWhenExists() {
        LocalFileStorageService service = newService();
        String rel = ".uploads/sess-3";
        service.appendChunk(rel, 0, new byte[42]).await().indefinitely();

        long size = service.size(rel).await().indefinitely();

        assertEquals(42L, size);
    }

    @Test
    void shouldMoveTempToFinalCreatingTargetParent() {
        LocalFileStorageService service = newService();
        String tempRel = ".uploads/sess-4";
        String finalRel = "kb-1/cat-1/doc-4.pdf";
        service.appendChunk(tempRel, 0, "payload".getBytes(StandardCharsets.UTF_8)).await().indefinitely();

        service.move(tempRel, finalRel).await().indefinitely();

        assertFalse(Files.exists(tempDir.resolve(tempRel)));
        assertTrue(Files.exists(tempDir.resolve(finalRel)));
        assertContent(finalRel, "payload".getBytes(StandardCharsets.UTF_8));
    }

    private void assertContent(String rel, byte[] expected) {
        try {
            assertArrayEquals(expected, Files.readAllBytes(tempDir.resolve(rel)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
