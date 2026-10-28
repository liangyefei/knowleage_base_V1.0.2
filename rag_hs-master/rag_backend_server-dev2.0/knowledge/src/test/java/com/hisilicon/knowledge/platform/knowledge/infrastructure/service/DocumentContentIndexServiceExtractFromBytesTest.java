package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DocumentContentIndexService#extractFromBytes} 按需抽取单测（版本对比复用入口）：
 * 文本直读 / 图片 SKIPPED / 未知类型 SKIPPED / 损坏二进制 FAILED 兜底。
 * <p>不触存储与仓储（extractFromBytes 只依赖 vertx + 解析器），构造器其余参数传 null。</p>
 */
class DocumentContentIndexServiceExtractFromBytesTest {

    private static final Vertx VERTX = Vertx.vertx();

    private final DocumentContentIndexService service =
            new DocumentContentIndexService(null, null, VERTX, 1000, 5);

    @AfterAll
    static void closeVertx() {
        VERTX.close().await().atMost(Duration.ofSeconds(5));
    }

    @Test
    void shouldReadTxtDirectly() {
        DocumentContentIndexService.ExtractedText r = service
                .extractFromBytes("hello\nworld".getBytes(StandardCharsets.UTF_8), "kb-1/说明.txt", 1000, 5)
                .await().atMost(Duration.ofSeconds(10));
        assertEquals(DocumentContentIndexService.STATUS_READY, r.status());
        assertEquals("hello\nworld", r.text());
    }

    @Test
    void shouldReadCsvDirectly() {
        // csv 为纯文本，与 txt 同口径 UTF-8 直读
        DocumentContentIndexService.ExtractedText r = service
                .extractFromBytes("name,version\nCV620,v1.0".getBytes(StandardCharsets.UTF_8),
                        "kb-1/芯片清单.csv", 1000, 5)
                .await().atMost(Duration.ofSeconds(10));
        assertEquals(DocumentContentIndexService.STATUS_READY, r.status());
        assertEquals("name,version\nCV620,v1.0", r.text());
    }

    @Test
    void shouldSkipImage() {
        DocumentContentIndexService.ExtractedText r = service
                .extractFromBytes(new byte[]{1, 2, 3}, "kb-1/pic.png", 1000, 5)
                .await().atMost(Duration.ofSeconds(10));
        assertEquals(DocumentContentIndexService.STATUS_SKIPPED, r.status());
        assertNull(r.text());
    }

    @Test
    void shouldSkipUnknownExtension() {
        DocumentContentIndexService.ExtractedText r = service
                .extractFromBytes(new byte[]{1, 2, 3}, "kb-1/blob.xyz", 1000, 5)
                .await().atMost(Duration.ofSeconds(10));
        assertEquals(DocumentContentIndexService.STATUS_SKIPPED, r.status());
    }

    @Test
    void shouldRecoverFailedForCorruptBinary() {
        // docx 扩名 + 非 zip 字节 → 解析异常 → FAILED 兜底（不向上抛）
        DocumentContentIndexService.ExtractedText r = service
                .extractFromBytes("not-a-zip".getBytes(StandardCharsets.UTF_8), "kb-1/doc.docx", 1000, 5)
                .await().atMost(Duration.ofSeconds(10));
        assertEquals(DocumentContentIndexService.STATUS_FAILED, r.status());
        assertNull(r.text());
    }
}
