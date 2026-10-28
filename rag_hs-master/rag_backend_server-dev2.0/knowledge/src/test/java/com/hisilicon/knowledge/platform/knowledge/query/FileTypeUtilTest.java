package com.hisilicon.knowledge.platform.knowledge.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileTypeUtil#fromPath} 单测。
 * <p>验证从 {@code file_path} 反解扩展名的正确性——这是修复 {@code .h} 被误显为
 * {@code TXT} 的核心：权威源为文件名扩展名，不再依赖客户端 MIME。</p>
 */
class FileTypeUtilTest {

    @Test
    void fromPath_h() {
        assertEquals("h", FileTypeUtil.fromPath("kb-1/aio_pub.h").orElse(null));
    }

    @Test
    void fromPath_hWithCategory() {
        assertEquals("h", FileTypeUtil.fromPath("kb-1/cat-1/aio_pub.h").orElse(null));
    }

    @Test
    void fromPath_pdf() {
        assertEquals("pdf", FileTypeUtil.fromPath("kb-1/doc.pdf").orElse(null));
    }

    /** 末段含多点：取最后一个点之后（与 extractExtension 一致） */
    @Test
    void fromPath_multiDot() {
        assertEquals("gz", FileTypeUtil.fromPath("kb-1/a.tar.gz").orElse(null));
    }

    /** 无扩展名（makefile）→ 空 Optional，不再误显为 txt */
    @Test
    void fromPath_noExtension() {
        assertTrue(FileTypeUtil.fromPath("kb-1/makefile").isEmpty());
    }

    @Test
    void fromPath_trailingDot() {
        assertTrue(FileTypeUtil.fromPath("kb-1/readme.").isEmpty());
    }

    @Test
    void fromPath_null() {
        assertTrue(FileTypeUtil.fromPath(null).isEmpty());
    }

    @Test
    void fromPath_blank() {
        assertTrue(FileTypeUtil.fromPath("   ").isEmpty());
    }

    @Test
    void fromPath_uppercaseNormalized() {
        assertEquals("h", FileTypeUtil.fromPath("kb-1/AIO_PUB.H").orElse(null));
    }

    /** 目录段含点不污染扩展名提取 */
    @Test
    void fromPath_dotInDirSegment() {
        assertEquals("h", FileTypeUtil.fromPath("kb.1/cat/a.h").orElse(null));
    }

    @Test
    void fromPath_backslashSeparator() {
        assertEquals("h", FileTypeUtil.fromPath("kb-1\\a.h").orElse(null));
    }

    @Test
    void fromPath_bareName() {
        assertEquals("h", FileTypeUtil.fromPath("a.h").orElse(null));
    }
}
