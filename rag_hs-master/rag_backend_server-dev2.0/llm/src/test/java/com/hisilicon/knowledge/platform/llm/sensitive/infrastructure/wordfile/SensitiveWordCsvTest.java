package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.apache.commons.csv.CSVFormat.DEFAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词导入 CSV 工具单测（5 列同构格式，纯 JUnit）
 */
class SensitiveWordCsvTest {

    private static final String HEADER = "标签名称,标签标识,标签是否启用,敏感词,词是否启用";

    @Test
    void shouldParseWithHeaderAndBom() throws Exception {
        byte[] content = ("\uFEFF" + HEADER + "\n涉政,POLITICAL,启用,机密词汇,启用\n").getBytes(StandardCharsets.UTF_8);
        List<SensitiveWordImportRow> rows = SensitiveWordCsv.parseRows(content);

        assertEquals(1, rows.size());
        assertEquals("涉政", rows.get(0).textLabel());
        assertEquals("POLITICAL", rows.get(0).identification());
        assertEquals("启用", rows.get(0).tagEnableRaw());
        assertEquals("机密词汇", rows.get(0).word());
        assertEquals("启用", rows.get(0).wordEnableRaw());
    }

    @Test
    void shouldParseWithoutBomAndBlankEnableColumns() throws Exception {
        byte[] content = (HEADER + "\n涉政,,,\n").getBytes(StandardCharsets.UTF_8);
        List<SensitiveWordImportRow> rows = SensitiveWordCsv.parseRows(content);

        assertEquals(1, rows.size());
        assertEquals("涉政", rows.get(0).textLabel());
        assertNull(rows.get(0).identification());
        assertNull(rows.get(0).tagEnableRaw());
        assertNull(rows.get(0).word(), "敏感词列空白 → null（由命令侧校验拒绝）");
        assertNull(rows.get(0).wordEnableRaw());
    }

    @Test
    void shouldSkipBlankRowsAndTrimCells() throws Exception {
        byte[] content = (HEADER + "\n涉政,POLITICAL,启用, 词A ,停用\n,,,,\n涉政,POLITICAL,启用,词B,启用\n")
                .getBytes(StandardCharsets.UTF_8);
        List<SensitiveWordImportRow> rows = SensitiveWordCsv.parseRows(content);

        assertEquals(2, rows.size());
        assertEquals("词A", rows.get(0).word());
        assertEquals("停用", rows.get(0).wordEnableRaw());
        assertEquals("词B", rows.get(1).word());
    }

    @Test
    void shouldRejectHeaderMismatch() {
        byte[] content = "词库\n词A\n".getBytes(StandardCharsets.UTF_8);
        IOException e = assertThrows(IOException.class, () -> SensitiveWordCsv.parseRows(content));
        assertTrue(e.getMessage().contains("表头不匹配"));
    }

    @Test
    void shouldSkipShortHeaderlessLinesAfterValidHeader() throws Exception {
        // 尾随空行（列数不足）不抛错，按空行跳过
        byte[] content = (HEADER + "\n涉政,POLITICAL,启用,词A,启用\n\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(1, SensitiveWordCsv.parseRows(content).size());
    }

    /**
     * 借 commons-csv 生成带引号字段（含逗号/换行的词），校验 RFC4180 转义正确
     */
    @Test
    void shouldParseQuotedCell() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            DEFAULT.print(writer).printRecord((Object[]) SensitiveWordExcel.HEADERS);
            DEFAULT.print(writer).printRecord("涉政", "POLITICAL", "启用", "词,含逗号", "启用");
        }
        List<SensitiveWordImportRow> rows = SensitiveWordCsv.parseRows(out.toByteArray());
        assertEquals("词,含逗号", rows.get(0).word());
    }
}
