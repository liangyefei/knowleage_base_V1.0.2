package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import static com.hisilicon.knowledge.platform.knowledge.infrastructure.service.RarTestArchiveBuilder.rar4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RarArchiveInspector} 单元测试（纯 JUnit；口径对齐 {@code ZipArchiveInspectorTest}）。
 */
class RarArchiveInspectorTest {

    private static final long UNLIMITED_TOTAL = Long.MAX_VALUE;

    private static java.util.Optional<ZipArchiveInspector.Violation> inspect(
            byte[] rar, int maxEntries, long maxTotalBytes, int maxDepth) {
        InputStream in = new ByteArrayInputStream(rar);
        return RarArchiveInspector.inspect(in, maxEntries, maxTotalBytes, maxDepth);
    }

    @Test
    void detectsEntryCountExceededRar4() throws IOException {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("a.txt", "1"),
                new RarTestArchiveBuilder.Entry("b.txt", "2"),
                new RarTestArchiveBuilder.Entry("c.txt", "3"));
        var v = inspect(rar, 2, UNLIMITED_TOTAL, 50);
        assertEquals(ZipArchiveInspector.ViolationType.ENTRY_COUNT_EXCEEDED, v.get().type());
        assertEquals(2, v.get().maxValue());
    }

    @Test
    void detectsTotalSizeExceededRar5() throws IOException {
        byte[] real = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/rar/junrar-rar5.rar"));
        var v = inspect(real, 100, 10, 50);
        assertEquals(ZipArchiveInspector.ViolationType.TOTAL_SIZE_EXCEEDED, v.get().type());
    }

    @Test
    void detectsDepthExceededRar4() throws IOException {
        byte[] rar = rar4(new RarTestArchiveBuilder.Entry("a/b/c/doc.txt", "x"));
        var v = inspect(rar, 100, UNLIMITED_TOTAL, 2);
        assertEquals(ZipArchiveInspector.ViolationType.DEPTH_EXCEEDED, v.get().type());
    }

    /** 分卷在预检即拦截（不用等到解压） */
    @Test
    void detectsMultiVolumeRar4() throws IOException {
        byte[] rar = rar4(true, false, new RarTestArchiveBuilder.Entry("p.txt", "x"));
        var v = inspect(rar, 100, UNLIMITED_TOTAL, 10);
        assertEquals(ZipArchiveInspector.ViolationType.MULTI_VOLUME, v.get().type());
    }

    @Test
    void detectsMultiVolumeRar5RealFixtureNoFalsePositive() throws IOException {
        // RAR5 分卷无法从单文件头可靠判定（真实 WinRAR 单卷包也可能带 VOLUME 位），
        // 不在预检拦截，交由解压期兜底；此处验证真实单卷包不误报
        byte[] real = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/rar/junrar-rar5.rar"));
        assertTrue(inspect(real, 100, UNLIMITED_TOTAL, 10).isEmpty());
    }

    @Test
    void passesCompliantRar4() throws IOException {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("doc.pdf", "pdf bytes"),
                new RarTestArchiveBuilder.Entry("sub/note.md", "note"));
        assertTrue(inspect(rar, 100, UNLIMITED_TOTAL, 10).isEmpty());
    }

    /** 穿越条目不会被消费，不计入条目数（口径对齐 ZipArchiveInspector） */
    @Test
    void traversalEntriesNotCounted() throws IOException {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("../evil.txt", "x"),
                new RarTestArchiveBuilder.Entry("ok.txt", "y"));
        assertTrue(inspect(rar, 1, UNLIMITED_TOTAL, 10).isEmpty());
    }

    /** 非压缩包字节：降级 empty 回流式解压兜底（不抛异常） */
    @Test
    void nonRarBytesFallBackToEmptyInsteadOfThrowing() {
        byte[] garbage = "this is definitely not a rar archive".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(inspect(garbage, 10, UNLIMITED_TOTAL, 10).isEmpty());
    }

    /** 截断的 rar（仅魔数）：降级 empty，不新增硬失败 */
    @Test
    void truncatedRarFallsBackToEmpty() {
        byte[] truncated = new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00, 0x00};
        assertTrue(inspect(truncated, 10, UNLIMITED_TOTAL, 10).isEmpty());
    }

    /**
     * 真实 WinRAR RAR5 样本回归（含 main header Locator extra 区 + 非 UTF-8 内容 csv，
     * fixture 为用户实测样本 2并发.rar）：预检不误报，且严格解析（非降级 empty——
     * 回归守护 inspectRar5 的条件 vint 读取逻辑：修复前 extra/data 区不存在的块会被
     * 幽灵 vintLen 多推进，条目元数据可能读歪或解析降级。
     */
    @Test
    void realLocatorRar5PassesPrecheck() throws IOException {
        byte[] real = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/rar/locator-rar5.rar"));
        assertTrue(inspect(real, 100, UNLIMITED_TOTAL, 10).isEmpty());
        // 单条目上限收紧到 0：若解析真的读到了条目（而非降级/漏读），应命中条目数违规——
        // 证明解析路径真实经过 FILE_HEADER 且条目名/大小读取正确
        var v = inspect(real, 0, UNLIMITED_TOTAL, 10);
        assertEquals(ZipArchiveInspector.ViolationType.ENTRY_COUNT_EXCEEDED, v.get().type());
        // 声明总量 419：恰好卡在上限不违规
        assertTrue(inspect(real, 100, 419, 10).isEmpty());
        // 声明总量 418：命中总量违规（验证 unpSize=419 被正确读取）
        assertEquals(ZipArchiveInspector.ViolationType.TOTAL_SIZE_EXCEEDED,
                inspect(real, 100, 418, 10).get().type());
    }
}
