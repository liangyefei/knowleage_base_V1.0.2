package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.hisilicon.knowledge.platform.knowledge.infrastructure.service.RarTestArchiveBuilder.pathsOf;
import static com.hisilicon.knowledge.platform.knowledge.infrastructure.service.RarTestArchiveBuilder.rar4;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RarFileExtractor} 单元测试（纯 JUnit，包为内存构造的 store 包，无外部 rar 工具依赖）。
 * <p>语义对齐 {@code ZipFileExtractorTest}：正常解压、中文名、路径穿越跳过、超限终止、
 * 非 RAR 魔数识别、分卷/加密整体失败、RAR4/RAR5 双格式。</p>
 */
class RarFileExtractorTest {

    // ==================== RAR4 ====================

    @Test
    void shouldExtractRar4Store() throws Exception {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("doc.txt", "hello"),
                new RarTestArchiveBuilder.Entry("a/b/code.py", "print(1)"));
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(rar);
        assertEquals(List.of("doc.txt", "a/b/code.py"), pathsOf(entries));
        assertEquals("hello", new String(entries.get(0).content(), StandardCharsets.UTF_8));
        assertEquals("print(1)", new String(entries.get(1).content(), StandardCharsets.UTF_8));
    }

    /** RAR4 encoded-unicode 中文名条目 */
    @Test
    void shouldDecodeUnicodeEntryNameRar4() throws Exception {
        byte[] rar = rar4(new RarTestArchiveBuilder.Entry("中文说明.txt", "内容",
                true));
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(rar);
        assertEquals(1, entries.size());
        assertEquals("中文说明.txt", entries.get(0).path());
    }

    @Test
    void shouldSkipTraversalEntryRar4() throws Exception {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("../evil.txt", "x"),
                new RarTestArchiveBuilder.Entry("ok.txt", "y"));
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(rar);
        assertEquals(List.of("ok.txt"), pathsOf(entries));
    }

    @Test
    void shouldSkipEmptyFileAndDirectoryRar4() throws Exception {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("empty.txt", new byte[0]),
                new RarTestArchiveBuilder.Entry("dir/", new byte[0]),
                new RarTestArchiveBuilder.Entry("real.txt", "v"));
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(rar);
        assertEquals(List.of("real.txt"), pathsOf(entries));
    }

    @Test
    void shouldFailWhenEntryCountExceededRar4() throws Exception {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("1.txt", "a"),
                new RarTestArchiveBuilder.Entry("2.txt", "b"),
                new RarTestArchiveBuilder.Entry("3.txt", "c"));
        ZipFileExtractor.ZipLimitExceededException ex = assertThrows(
                ZipFileExtractor.ZipLimitExceededException.class,
                () -> RarFileExtractor.extract(rar, 2, RarFileExtractor.DEFAULT_MAX_TOTAL_BYTES));
        assertTrue(ex.entryCountExceeded);
        assertEquals(2, ex.maxValue);
    }

    @Test
    void shouldFailWhenTotalSizeExceededRar4() throws Exception {
        byte[] rar = rar4(
                new RarTestArchiveBuilder.Entry("1.txt", "aaaa"),
                new RarTestArchiveBuilder.Entry("2.txt", "bbbb"));
        ZipFileExtractor.ZipLimitExceededException ex = assertThrows(
                ZipFileExtractor.ZipLimitExceededException.class,
                () -> RarFileExtractor.extract(rar, 100, 5));
        assertEquals(5, ex.maxValue);
    }

    @Test
    void shouldRejectNonRarBytes() throws Exception {
        byte[] notRar = "这不是一个rar文件".getBytes(StandardCharsets.UTF_8);
        assertThrows(RarFileExtractor.NotRarFileException.class, () -> RarFileExtractor.extract(notRar));
    }

    /** zip 头≠rar：格式分岔不能互相误判 */
    @Test
    void shouldRejectZipBytesAsNotRar() throws Exception {
        byte[] zip = new byte[]{'P', 'K', 0x03, 0x04, 1, 2, 3};
        assertThrows(RarFileExtractor.NotRarFileException.class, () -> RarFileExtractor.extract(zip));
    }

    /** 分卷标志 → 整体失败（明确文案，不产部分结果） */
    @Test
    void shouldRejectMultiVolumeRar4() throws Exception {
        byte[] rar = rar4(true, false,
                new RarTestArchiveBuilder.Entry("part.txt", "x"));
        IOException ex = assertThrows(IOException.class, () -> RarFileExtractor.extract(rar));
        assertTrue(ex.getMessage().contains("分卷"), ex.getMessage());
    }

    /** 加密标志 → 整体失败（明确文案） */
    @Test
    void shouldRejectEncryptedRar4() throws Exception {
        byte[] rar = rar4(false, true,
                new RarTestArchiveBuilder.Entry("secret.txt", "x"));
        IOException ex = assertThrows(IOException.class, () -> RarFileExtractor.extract(rar));
        assertTrue(ex.getMessage().contains("加密"), ex.getMessage());
    }

    /** 流式入口（tus 链路用）与 byte[] 入口等价 */
    @Test
    void shouldExtractFromStream() throws Exception {
        byte[] rar = rar4(new RarTestArchiveBuilder.Entry("s.txt", "stream"));
        List<ZipFileExtractor.ZipFileEntry> fromStream = new ArrayList<>();
        RarFileExtractor.extract(new ByteArrayInputStream(rar), 100, 1 << 20,
                (path, content) -> fromStream.add(new ZipFileExtractor.ZipFileEntry(path, content)));
        assertEquals(List.of("s.txt"), pathsOf(fromStream));
        assertArrayEquals("stream".getBytes(StandardCharsets.UTF_8), fromStream.get(0).content());
    }

    // ==================== RAR5（7-Zip-JBinding；fixture 为 WinRAR 制作的 junrar-rar5.rar，
    // 2 个条目 FILE1.TXT/FILE2.TXT 各 7 字节。手工组包无法稳定构造 7z 认可的 RAR5，见 builder 注释） ====================

    private static byte[] realRar5() throws Exception {
        return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/rar/junrar-rar5.rar"));
    }

    @Test
    void shouldExtractRar5Store() throws Exception {
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(realRar5());
        assertEquals(List.of("FILE1.TXT", "FILE2.TXT"), pathsOf(entries));
        assertEquals("file1\r\n", new String(entries.get(0).content(), StandardCharsets.UTF_8));
        assertEquals("file2\r\n", new String(entries.get(1).content(), StandardCharsets.UTF_8));
    }

    /** 流式入口（tus 链路 RAR5 → 临时文件路径） */
    @Test
    void shouldExtractRar5FromStream() throws Exception {
        List<ZipFileExtractor.ZipFileEntry> fromStream = new ArrayList<>();
        RarFileExtractor.extract(new ByteArrayInputStream(realRar5()), 100, 1 << 20,
                (path, content) -> fromStream.add(new ZipFileExtractor.ZipFileEntry(path, content)));
        assertEquals(List.of("FILE1.TXT", "FILE2.TXT"), pathsOf(fromStream));
    }

    /** 真实 WinRAR RAR5 用户样本回归（main header 带 Locator extra 区，fixture=2并发.rar）：
     *  中文文件名 csv，内嵌 JNI 路径解出正确条目名与 419 字节内容 */
    @Test
    void shouldExtractRealLocatorRar5() throws Exception {
        byte[] real = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/rar/locator-rar5.rar"));
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(real, 100, 1 << 20, null);
        assertEquals(List.of("2并发.csv"), pathsOf(entries));
        assertEquals(419, entries.get(0).content().length);
    }

    @Test
    void shouldFailWhenEntryCountExceededRar5() throws Exception {
        assertThrows(ZipFileExtractor.ZipLimitExceededException.class,
                () -> RarFileExtractor.extract(realRar5(), 1, RarFileExtractor.DEFAULT_MAX_TOTAL_BYTES));
    }

    // ==================== 外部进程方案 B ====================

    /** 工具路径不存在 → 确定性失败，报错含启动失败原因（任何环境可跑） */
    @Test
    void shouldFailWithClearMessageWhenExternalToolMissing() throws Exception {
        IOException ex = assertThrows(IOException.class, () -> RarFileExtractor.extract(
                realRar5(), 100, 1 << 20, "definitely-missing-7zz-binary"));
        assertTrue(ex.getMessage().contains("外部解压工具启动失败"), ex.getMessage());
    }

    /** 有 7zz/7z 的环境下端到端验证外部进程路径（无工具环境自动跳过） */
    @Test
    void shouldExtractRar5ViaExternalToolWhenAvailable() throws Exception {
        Optional<String> tool = findExternalTool();
        org.junit.jupiter.api.Assumptions.assumeTrue(tool.isPresent(), "环境无 7zz/7z，跳过外部进程端到端用例");
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(
                realRar5(), 100, 1 << 20, tool.get());
        assertEquals(List.of("FILE1.TXT", "FILE2.TXT"), pathsOf(entries));
        assertEquals("file1\r\n", new String(entries.get(0).content(), StandardCharsets.UTF_8));
    }

    /** 定位外部工具：KB_ARCHIVE_EXTERNAL_TOOL 环境变量优先，其次 PATH 探测；未找到为空 {@code Optional} */
    private static Optional<String> findExternalTool() {
        String env = System.getenv("KB_ARCHIVE_EXTERNAL_TOOL");
        if (env != null && !env.isBlank() && new java.io.File(env).canExecute()) {
            return Optional.of(env);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        for (String candidate : windows ? new String[]{"7zz.exe", "7z.exe"} : new String[]{"7zz", "7z"}) {
            try {
                Process p = new ProcessBuilder(candidate, "-h").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    return Optional.of(candidate);
                }
                p.destroyForcibly();
            } catch (java.io.IOException | java.lang.InterruptedException ignored) {
                // PATH 上不存在该候选
            }
        }
        return Optional.empty();
    }
}
