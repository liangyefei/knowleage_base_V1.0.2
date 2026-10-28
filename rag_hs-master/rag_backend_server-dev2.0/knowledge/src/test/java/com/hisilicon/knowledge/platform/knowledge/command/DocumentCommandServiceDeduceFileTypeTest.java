package com.hisilicon.knowledge.platform.knowledge.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DocumentCommandService#deduceFileType} 单测。
 * <p>验证策略已反转为<b>扩展名优先</b>：有扩展名时一律返回扩展名，不再让客户端 MIME
 * 覆盖（修复 {@code .h} 被报成 {@code text/plain} 后误显 {@code txt}）。
 * 仅当扩展名缺失（如 {@code makefile}）才退回 MIME 兜底。</p>
 */
class DocumentCommandServiceDeduceFileTypeTest {

    @Test
    void extensionWinsOverMime_h() {
        assertEquals("h", DocumentCommandService.deduceFileType("text/plain", "h"));
    }

    @Test
    void extensionWinsForUnmappedMime_dtsi() {
        assertEquals("dtsi", DocumentCommandService.deduceFileType("text/plain", "dtsi"));
    }

    @Test
    void mimeNull_extensionOnly_py() {
        assertEquals("py", DocumentCommandService.deduceFileType(null, "py"));
    }

    @Test
    void octetStream_extensionWins() {
        assertEquals("h", DocumentCommandService.deduceFileType("application/octet-stream", "h"));
    }

    @Test
    void extensionEmpty_mimeFallback() {
        assertEquals("txt", DocumentCommandService.deduceFileType("text/plain", ""));
    }

    @Test
    void extensionNull_mimeFallback() {
        assertEquals("txt", DocumentCommandService.deduceFileType("text/plain", null));
    }

    @Test
    void bothEmpty_unknown() {
        assertEquals("unknown", DocumentCommandService.deduceFileType(null, ""));
    }

    @Test
    void bothNull_unknown() {
        assertEquals("unknown", DocumentCommandService.deduceFileType(null, null));
    }

    @Test
    void pdf() {
        assertEquals("pdf", DocumentCommandService.deduceFileType("application/pdf", "pdf"));
    }
}
