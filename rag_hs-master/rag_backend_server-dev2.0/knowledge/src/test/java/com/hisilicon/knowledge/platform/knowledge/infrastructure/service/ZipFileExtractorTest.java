package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZipFileExtractor 单元测试（纯 JUnit，无 Quarkus 上下文）
 * <p>重点覆盖真实环境踩过的坑：GBK 中文条目名、非标准 header 容错、非 ZIP 文件识别。</p>
 */
class ZipFileExtractorTest {

    /** GBK 字符集常量（避免方法体内重复 Charset.forName，G.EXP.04） */
    private static final Charset GBK = Charset.forName("GBK");

    /** 标准 ZIP（UTF-8 条目名，7-Zip 默认行为） */
    @Test
    void shouldExtractStandardZip() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"hardware/board/spec.pdf", "pdf-content"},
                {"hardware/board/readme.md", "md-content"}
        });

        List<ZipFileExtractor.ZipFileEntry> entries = ZipFileExtractor.extract(zip);

        assertEquals(2, entries.size());
        assertEquals("hardware/board/spec.pdf", entries.get(0).path());
        assertEquals("pdf-content", new String(entries.get(0).content(), StandardCharsets.UTF_8));
    }

    /** Windows 压缩的中文名 ZIP（GBK 条目名，无 EFS flag）——真实环境踩坑场景 */
    @Test
    void shouldExtractGbkChineseNames() throws IOException {
        byte[] zip = buildZip(GBK, new String[][]{
                {"中文目录/规格书.pdf", "content-1"},
                {"测试上传/使用说明.txt", "content-2"}
        });

        List<ZipFileExtractor.ZipFileEntry> entries = ZipFileExtractor.extract(zip);

        assertEquals(2, entries.size(), "GBK 中文名条目应全部解出");
        assertEquals("中文目录/规格书.pdf", entries.get(0).path(), "GBK 中文名应正确解码");
        assertEquals("测试上传/使用说明.txt", entries.get(1).path());
    }

    /** 非 ZIP 字节 → NotAZipFileException */
    @Test
    void shouldRejectNonZipBytes() {
        byte[] notZip = "这不是一个zip文件".getBytes(StandardCharsets.UTF_8);

        assertThrows(ZipFileExtractor.NotAZipFileException.class,
                () -> ZipFileExtractor.extract(notZip));
    }

    /** 空字节 → NotAZipFileException（不足 4 字节） */
    @Test
    void shouldRejectEmptyBytes() {
        assertThrows(ZipFileExtractor.NotAZipFileException.class,
                () -> ZipFileExtractor.extract(new byte[0]));
    }

    /** 损坏条目（篡改第二个条目的 LOC 签名）→ 明确抛异常（不静默吞掉损坏包） */
    @Test
    void shouldThrowOnCorruptEntry() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"good-1.pdf", "content-1"},
                {"good-2.pdf", "content-2"}
        });

        // 破坏第二个条目的 local file header 签名（PK\x03\x04 → 0x00 0x00 0x00 0x00）
        int secondLoc = findSecondLocSignature(zip);
        assertTrue(secondLoc > 0, "应能找到第二个条目头");
        for (int i = 0; i < 4; i++) {
            zip[secondLoc + i] = 0x00;
        }

        // 损坏的 zip 应抛异常（上层转为"压缩包解压失败"），而不是静默产出部分结果
        assertThrows(IOException.class, () -> ZipFileExtractor.extract(zip));
    }

    /** 目录条目被跳过 */
    @Test
    void shouldSkipDirectoryEntries() throws IOException {
        byte[] zip = buildZipWithDirectory(StandardCharsets.UTF_8);

        List<ZipFileExtractor.ZipFileEntry> entries = ZipFileExtractor.extract(zip);

        assertEquals(1, entries.size());
        assertEquals("dir/file.txt", entries.get(0).path());
    }

    /** 路径穿越条目被跳过 */
    @Test
    void shouldSkipPathTraversalEntries() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"../../evil.pdf", "content"},
                {"safe.pdf", "content"}
        });

        List<ZipFileExtractor.ZipFileEntry> entries = ZipFileExtractor.extract(zip);

        assertEquals(1, entries.size());
        assertEquals("safe.pdf", entries.get(0).path());
    }

    /** 条目数超过上限 → 整体失败（防超长处理链/zip 炸弹） */
    @Test
    void shouldRejectWhenEntryCountExceedsLimit() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"a-1.txt", "1"},
                {"a-2.txt", "2"},
                {"a-3.txt", "3"}
        });

        ZipFileExtractor.ZipLimitExceededException ex = assertThrows(
                ZipFileExtractor.ZipLimitExceededException.class,
                () -> ZipFileExtractor.extract(zip, 2, ZipFileExtractor.DEFAULT_MAX_TOTAL_BYTES));

        assertTrue(ex.getMessage().contains("条目数超过限制"), "错误消息应指明是条目数超限: " + ex.getMessage());
        assertTrue(ex.entryCountExceeded, "应标记为条目数超限类型");
        assertEquals(2, ex.maxValue, "应携带上限值");
    }

    /** 解压总量超过上限 → 整体失败（防高压缩比 zip 炸弹内存溢出） */
    @Test
    void shouldRejectWhenTotalBytesExceedLimit() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"big.txt", "0123456789"}
        });

        ZipFileExtractor.ZipLimitExceededException ex = assertThrows(
                ZipFileExtractor.ZipLimitExceededException.class,
                () -> ZipFileExtractor.extract(zip, ZipFileExtractor.DEFAULT_MAX_ENTRIES, 5));

        assertTrue(ex.getMessage().contains("解压总量超过限制"), "错误消息应指明是总量超限: " + ex.getMessage());
        assertFalse(ex.entryCountExceeded, "应标记为总量超限类型");
        assertEquals(5, ex.maxValue, "应携带上限值");
    }

    // ======================== InputStream 流式变体（批量 complete 用） ========================

    /** InputStream 变体与 byte[] 变体解出一致条目 */
    @Test
    void extractInputStreamMatchesByteArray() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"hardware/board/doc.pdf", "PDF-1"},
                {"readme.txt", "TXT-1"}});
        var fromBytes = ZipFileExtractor.extract(zip);
        var fromStream = ZipFileExtractor.extract(new java.io.ByteArrayInputStream(zip));
        assertEquals(fromBytes.size(), fromStream.size(), "条目数一致");
        for (int i = 0; i < fromBytes.size(); i++) {
            assertEquals(fromBytes.get(i).path(), fromStream.get(i).path());
            assertArrayEquals(fromBytes.get(i).content(), fromStream.get(i).content());
        }
    }

    /** InputStream 变体 GBK 中文名正确解码（decodeEntryName 自适配：UTF-8 优先 GBK fallback） */
    @Test
    void extractInputStreamGbkEntryNames() throws IOException {
        byte[] zip = buildZip(GBK, new String[][]{
                {"寄存器手册.pdf", "内容"}, {"芯片说明.txt", "说明"}});
        var entries = ZipFileExtractor.extract(new java.io.ByteArrayInputStream(zip));
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.path().equals("寄存器手册.pdf")));
    }

    /** InputStream 变体跳过目录条目（与 byte[] 一致） */
    @Test
    void extractInputStreamSkipsDirectories() throws IOException {
        byte[] zip = buildZipWithDirectory(StandardCharsets.UTF_8);
        var entries = ZipFileExtractor.extract(new java.io.ByteArrayInputStream(zip));
        assertEquals(1, entries.size(), "目录条目应被跳过");
        assertEquals("dir/file.txt", entries.get(0).path());
    }

    // ======================== 回调式流式解压（批量 5GB zip complete 用） ========================

    /** 回调式解压与 List 变体条目一致 */
    @Test
    void callbackMatchesList() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"hardware/board/doc.pdf", "PDF-1"},
                {"readme.txt", "TXT-1"},
                {"dir/notes.md", "MD-1"}});
        var fromList = ZipFileExtractor.extract(zip);
        java.util.List<String> seen = new java.util.ArrayList<>();
        int[] count = {0};
        ZipFileExtractor.extract(new java.io.ByteArrayInputStream(zip),
                ZipFileExtractor.DEFAULT_MAX_ENTRIES, ZipFileExtractor.DEFAULT_MAX_TOTAL_BYTES,
                (path, content) -> {
                    count[0]++;
                    seen.add(path);
                });
        assertEquals(fromList.size(), count[0], "回调条目数与 List 一致");
        assertEquals(fromList.stream().map(ZipFileExtractor.ZipFileEntry::path).toList(), seen);
    }

    /** 回调 consumer 抛 IOException 终止整个解压（不当作损坏条目跳过） */
    @Test
    void callbackAbortOnConsumerException() throws IOException {
        byte[] zip = buildZip(StandardCharsets.UTF_8, new String[][]{
                {"a.txt", "1"}, {"b.txt", "2"}, {"c.txt", "3"}});
        int[] processed = {0};
        java.io.IOException thrown = assertThrows(java.io.IOException.class, () ->
                ZipFileExtractor.extract(new java.io.ByteArrayInputStream(zip),
                        ZipFileExtractor.DEFAULT_MAX_ENTRIES, ZipFileExtractor.DEFAULT_MAX_TOTAL_BYTES,
                        (path, content) -> {
                            processed[0]++;
                            if (processed[0] == 2) {
                                throw new java.io.IOException("模拟落库失败");
                            }
                        }));
        assertTrue(thrown.getMessage().contains("模拟落库失败"), "consumer 异常应传播");
        assertEquals(2, processed[0], "第 2 条目抛出后终止，不再处理后续");
    }

    /** 回调式解压逐条目流式（consumer 内 content 仅当前条目，验证不一次性持有全部） */
    @Test
    void callbackStreamsOneAtATime() throws IOException {
        byte[] zip = buildZipWithDirectory(StandardCharsets.UTF_8); // dir/ + dir/file.txt
        java.util.List<byte[]> captured = new java.util.ArrayList<>();
        ZipFileExtractor.extract(new java.io.ByteArrayInputStream(zip),
                ZipFileExtractor.DEFAULT_MAX_ENTRIES, ZipFileExtractor.DEFAULT_MAX_TOTAL_BYTES,
                (path, content) -> captured.add(content));
        assertEquals(1, captured.size(), "目录条目跳过，仅 1 文件");
        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), captured.get(0));
    }

    // ======================== 辅助方法 ========================

    /** 用指定字符集生成 zip（多条目，纯文件） */
    private static byte[] buildZip(Charset charset, String[][] files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, charset)) {
            for (String[] file : files) {
                zos.putNextEntry(new ZipEntry(file[0]));
                zos.write(file[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 生成含目录条目的 zip */
    private static byte[] buildZipWithDirectory(Charset charset) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, charset)) {
            zos.putNextEntry(new ZipEntry("dir/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dir/file.txt"));
            zos.write("content".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 查找第二个 local file header 签名（PK\x03\x04）的位置 */
    private static int findSecondLocSignature(byte[] zip) {
        int first = -1;
        for (int i = 0; i < zip.length - 3; i++) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4B && zip[i + 2] == 0x03 && zip[i + 3] == 0x04) {
                if (first < 0) {
                    first = i;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }
}
