package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 WinRAR 产物 RAR4 样本回归（junrar 官方仓库 fixture，GitHub blob base64 落地）。
 * <p>目的：验证 RarFileExtractor 对真实 RAR4 压缩包的端到端解压——
 * 内存组包单测仅覆盖 store（不压缩）路径，真实样本补足 junrar 解压路径。</p>
 * <p>⚠️ 旧格式 RAR（RE~^ 魔数，RAR 1.4/1.5）刻意不支持：实测 junrar 7.5.5 解析抛
 * CorruptHeaderException，故魔数白名单不放行，上传得到明确的"不是有效的 RAR 文件"。</p>
 */
class RealRar4FixtureTest {

    private static byte[] load(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/rar/" + name));
    }

    @Test
    void shouldExtractRealWinRar4() throws Exception {
        List<ZipFileExtractor.ZipFileEntry> entries = RarFileExtractor.extract(load("junrar-rar4.rar"));
        assertEquals(List.of("FILE1.TXT", "FILE2.TXT"),
                entries.stream().map(ZipFileExtractor.ZipFileEntry::path).toList());
        assertEquals("file1\r\n", new String(entries.get(0).content(), StandardCharsets.UTF_8));
        assertEquals("file2\r\n", new String(entries.get(1).content(), StandardCharsets.UTF_8));
    }

    /** 真实包经预检不应误报（对齐 zip 预检降级语义） */
    @Test
    void inspectorShouldNotFalsePositiveOnRealRar4() throws Exception {
        var v = RarArchiveInspector.inspect(new java.io.ByteArrayInputStream(load("junrar-rar4.rar")),
                100, Long.MAX_VALUE, 10);
        assertTrue(v.isEmpty(), "真实 rar4 不应命中预检违规: " + v);
    }

    /** 旧格式 RE~^ 被明确拒绝为"不是有效的 RAR"（junrar 无法解析，刻意不放行） */
    @Test
    void oldFormatRarIsRejectedCleanly() throws Exception {
        byte[] old = load("junrar-rar14-solid.rar");
        // 魔数检查在解压前拦截，报错明确
        RarFileExtractor.NotRarFileException ex = assertThrows(
                RarFileExtractor.NotRarFileException.class, () -> RarFileExtractor.extract(old));
        assertTrue(ex.getMessage().contains("不是有效的 RAR 文件"), ex.getMessage());
    }
}
