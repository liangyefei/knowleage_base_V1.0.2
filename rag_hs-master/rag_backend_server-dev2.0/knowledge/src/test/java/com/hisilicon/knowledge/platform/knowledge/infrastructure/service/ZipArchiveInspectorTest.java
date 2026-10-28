package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ZipArchiveInspector} 单元测试（不起容器，纯静态工具）
 */
class ZipArchiveInspectorTest {

    private static final long UNLIMITED_TOTAL = Long.MAX_VALUE / 4;

    // ========== 各违规类型 ==========

    @Test
    void detectsEntryCountExceeded() throws IOException {
        // 3 个文件条目，上限 2 → 第 3 条目计数越限
        byte[] zip = zipOf(Map.of(
                "a.txt", "aaaa",
                "b.txt", "bbbb",
                "c.txt", "cccc"));
        Optional<ZipArchiveInspector.Violation> v = inspect(zip, 2, UNLIMITED_TOTAL, 50);
        assertTrue(v.isPresent());
        assertEquals(ZipArchiveInspector.ViolationType.ENTRY_COUNT_EXCEEDED, v.get().type());
        assertEquals(2, v.get().maxValue());
    }

    @Test
    void detectsTotalSizeExceeded() throws IOException {
        // 两个 8 字节条目共 16 字节，总量上限 10
        byte[] zip = zipOf(Map.of(
                "one.bin", "12345678",
                "two.bin", "87654321"));
        Optional<ZipArchiveInspector.Violation> v = inspect(zip, 100, 10, 50);
        assertTrue(v.isPresent());
        assertEquals(ZipArchiveInspector.ViolationType.TOTAL_SIZE_EXCEEDED, v.get().type());
        assertEquals(10, v.get().maxValue());
    }

    @Test
    void detectsDepthExceeded() throws IOException {
        // a/b/c/doc.txt 层级 4 > 上限 2
        byte[] zip = zipOf(Map.of("a/b/c/doc.txt", "content"));
        Optional<ZipArchiveInspector.Violation> v = inspect(zip, 100, UNLIMITED_TOTAL, 2);
        assertTrue(v.isPresent());
        assertEquals(ZipArchiveInspector.ViolationType.DEPTH_EXCEEDED, v.get().type());
        assertEquals(2, v.get().maxValue());
    }

    @Test
    void backslashPathCountsAsHierarchy() throws IOException {
        // Windows 反斜杠条目名清洗为正斜杠后计层级：w/sub/file.docx = 3 段
        byte[] zip = zipOf(Map.of("w\\sub\\file.docx", "content"));
        Optional<ZipArchiveInspector.Violation> v = inspect(zip, 100, UNLIMITED_TOTAL, 2);
        assertTrue(v.isPresent());
        assertEquals(ZipArchiveInspector.ViolationType.DEPTH_EXCEEDED, v.get().type());
    }

    @Test
    void detectsSingleEntryTooLarge() throws IOException {
        // 注入单文件上限 5 字节，16 字节条目命中
        byte[] zip = zipOf(Map.of("blob.bin", "0123456789abcdef"));
        Optional<ZipArchiveInspector.Violation> v =
                ZipArchiveInspector.inspect(channel(zip), 100, UNLIMITED_TOTAL, 50, 5);
        assertTrue(v.isPresent());
        assertEquals(ZipArchiveInspector.ViolationType.SINGLE_ENTRY_TOO_LARGE, v.get().type());
        assertEquals(5, v.get().maxValue());
    }

    // ========== 放行场景（不误伤） ==========

    @Test
    void passesShallowZipWithinLimits() throws IOException {
        byte[] zip = zipOf(Map.of(
                "readme.md", "# hello",
                "src/main.java", "class A {}"));
        Optional<ZipArchiveInspector.Violation> v = inspect(zip, 100, UNLIMITED_TOTAL, 10);
        assertTrue(v.isEmpty());
    }

    @Test
    void directoriesAndTraversalEntriesAreSkippedInCounting() throws IOException {
        // 目录条目与 ".." 穿越条目不会被流式解压消费，预检计数同样跳过
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("docs/", "");            // 目录条目
        entries.put("../escape.txt", "x");   // 穿越条目
        byte[] zip = zipOf(entries);
        Optional<ZipArchiveInspector.Violation> v = inspect(zip, 1, UNLIMITED_TOTAL, 2);
        assertTrue(v.isEmpty(), "目录与穿越条目不计入条目数");
    }

    @Test
    void parsesZip64CentralDirectory() throws IOException {
        // commons-compress 强制 zip64 模式写包（CD 带 zip64 extra 字段），宽松上限应正常解析通过
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zaos = new ZipArchiveOutputStream(bos)) {
            zaos.setUseZip64(Zip64Mode.Always);
            ZipArchiveEntry entry = new ZipArchiveEntry("z64/entry.txt");
            zaos.putArchiveEntry(entry);
            zaos.write("payload".getBytes(StandardCharsets.UTF_8));
            zaos.closeArchiveEntry();
        }
        Optional<ZipArchiveInspector.Violation> v =
                inspect(bos.toByteArray(), 1000, UNLIMITED_TOTAL, 10);
        assertTrue(v.isEmpty(), "zip64 包不应误报，实际：" + v);
    }

    // ========== 回退语义（解析失败不抛、回退流式解压） ==========

    @Test
    void corruptedArchiveFallsBackToEmptyInsteadOfThrowing() {
        // 有 ZIP 魔数但 central directory 缺失（截断的 local header 流）
        byte[] truncated = {(byte) 0x50, (byte) 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5};
        Optional<ZipArchiveInspector.Violation> v = inspect(truncated, 10, UNLIMITED_TOTAL, 10);
        assertTrue(v.isEmpty(), "CD 解析失败应回退 empty，交由流式解压兜底");
    }

    @Test
    void nonZipBytesFallBackToEmptyInsteadOfThrowing() {
        byte[] garbage = "this is definitely not a zip archive".getBytes(StandardCharsets.UTF_8);
        Optional<ZipArchiveInspector.Violation> v = inspect(garbage, 10, UNLIMITED_TOTAL, 10);
        assertTrue(v.isEmpty());
    }

    /** 预检占用并关闭传入的 channel（幂等关二次无害），供调用方免管理资源 */
    @Test
    void inspectClosesTheProvidedChannel() throws IOException {
        byte[] zip = zipOf(Map.of("f.txt", "data"));
        SeekableByteChannel ch = channel(zip);
        ZipArchiveInspector.inspect(ch, 10, UNLIMITED_TOTAL, 10);
        assertFalse(ch.isOpen());
    }

    // ========== 辅助 ==========

    private static Optional<ZipArchiveInspector.Violation> inspect(byte[] zip, int maxEntries,
                                                                   long maxTotalBytes, int maxDepth) {
        return ZipArchiveInspector.inspect(channel(zip), maxEntries, maxTotalBytes, maxDepth);
    }

    private static byte[] zipOf(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 只读内存 SeekableByteChannel（position 与 ByteBuffer 严格同步，满足 CD 随机读语义） */
    private static SeekableByteChannel channel(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data.clone()).asReadOnlyBuffer();
        return new SeekableByteChannel() {
            private boolean open = true;

            private void requireOpen() {
                if (!open) {
                    throw new IllegalStateException("channel 已关闭");
                }
            }

            @Override
            public int read(ByteBuffer dst) {
                requireOpen();
                if (!buf.hasRemaining()) {
                    return -1;
                }
                int n = Math.min(dst.remaining(), buf.remaining());
                for (int i = 0; i < n; i++) {
                    dst.put(buf.get());
                }
                return n;
            }

            @Override
            public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }

            @Override
            public long position() {
                requireOpen();
                return buf.position();
            }

            @Override
            public SeekableByteChannel position(long newPosition) {
                requireOpen();
                buf.position((int) newPosition);
                return this;
            }

            @Override
            public long size() {
                requireOpen();
                return buf.limit();
            }

            @Override
            public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException(); }

            @Override
            public boolean isOpen() { return open; }

            @Override
            public void close() { open = false; }
        };
    }
}
