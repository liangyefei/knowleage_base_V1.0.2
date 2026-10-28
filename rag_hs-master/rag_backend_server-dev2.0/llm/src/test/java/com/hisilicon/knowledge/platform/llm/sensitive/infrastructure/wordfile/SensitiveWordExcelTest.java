package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词 xlsx 工具单测（模板生成/解析/导出，纯 JUnit，POI 直接读写内存字节）
 */
class SensitiveWordExcelTest {

    @Test
    void shouldBuildTemplateWithGuideSheetAndNoDataRows() throws Exception {
        byte[] xlsx = SensitiveWordExcel.buildTemplate();

        // zip 魔数（xlsx 本质为 zip 包）：50 4B
        assertEquals((byte) 0x50, xlsx[0]);
        assertEquals((byte) 0x4B, xlsx[1]);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(2, wb.getNumberOfSheets());
            assertEquals("敏感词导入", wb.getSheetName(0));
            assertEquals("填写说明", wb.getSheetName(1));

            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            for (int i = 0; i < SensitiveWordExcel.HEADERS.length; i++) {
                assertEquals(SensitiveWordExcel.HEADERS[i], header.getCell(i).getStringCellValue());
            }
            // 模板不含示例数据行（避免用户忘删示例被导入）
            assertEquals(0, sheet.getLastRowNum());
            // 首行表头冻结（下拉滚动时表头保持可见）：pane ySplit=1 + frozen
            // （不用 PaneInformation 回读：POI 5.5.1 该对象 x/y 映射异常，直接断言 sheet XML）
            String sheetXml = ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).getCTWorksheet().xmlText();
            assertTrue(sheetXml.contains("state=\"frozen\"")
                    && sheetXml.matches("(?s).*ySplit=\"1(\\.0+)?\".*"));
            assertTrue(wb.getSheetAt(1).getRow(0).getCell(0).getStringCellValue().contains("说明"));
        }

        // 模板自身可被解析器读取（仅表头，无数据行）
        assertEquals(0, SensitiveWordExcel.parseRows(xlsx).size());
    }

    @Test
    void shouldParseDataRowsWithNumericCell() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("敏感词导入");
            Row header = sheet.createRow(0);
            for (int i = 0; i < SensitiveWordExcel.HEADERS.length; i++) {
                header.createCell(i).setCellValue(SensitiveWordExcel.HEADERS[i]);
            }
            // 数字单元格（用户误设数字格式）：DataFormatter 按显示文本读取
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("涉政");
            r1.createCell(1).setCellValue("POLITICAL");
            r1.createCell(2).setCellValue("启用");
            r1.createCell(3).setCellValue(12345);
            r1.createCell(4).setCellValue("停用");
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("  涉密  ");
            r2.createCell(3).setCellValue(" 词B ");
            wb.write(out);
            xlsx = out.toByteArray();
        }

        List<SensitiveWordImportRow> rows = SensitiveWordExcel.parseRows(xlsx);
        assertEquals(2, rows.size());
        assertEquals("12345", rows.get(0).word(), "数字单元格按显示文本读取");
        assertEquals("停用", rows.get(0).wordEnableRaw());
        assertEquals("涉密", rows.get(1).textLabel(), "单元格 trim");
        assertEquals("词B", rows.get(1).word());
    }

    @Test
    void shouldRejectHeaderMismatch() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("敏感词导入");
            sheet.createRow(0).createCell(0).setCellValue("词库");
            wb.write(out);
            xlsx = out.toByteArray();
        }

        assertThrows(IOException.class, () -> SensitiveWordExcel.parseRows(xlsx));
    }

    @Test
    void shouldRejectCorruptedBytes() {
        assertThrows(IOException.class, () -> SensitiveWordExcel.parseRows(
                "这不是xlsx".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * 导出与导入同构：writeExport 产物可被 parseRows 读回（回导闭环）
     */
    @Test
    void shouldExportAndRoundtrip() throws Exception {
        byte[] xlsx = SensitiveWordExcel.writeExport(List.of(
                new SensitiveWordExportRow("涉政", "POLITICAL", (short) 1, "机密词汇", (short) 1),
                new SensitiveWordExportRow("涉密", "SECRET", (short) 2, "词B", (short) 2)));

        List<SensitiveWordImportRow> rows = SensitiveWordExcel.parseRows(xlsx);
        assertEquals(2, rows.size());
        assertEquals("涉政", rows.get(0).textLabel());
        assertEquals("POLITICAL", rows.get(0).identification());
        assertEquals("启用", rows.get(0).tagEnableRaw());
        assertEquals("机密词汇", rows.get(0).word());
        assertEquals("启用", rows.get(0).wordEnableRaw());
        assertEquals("涉密", rows.get(1).textLabel());
        assertEquals("停用", rows.get(1).tagEnableRaw());
        assertEquals("停用", rows.get(1).wordEnableRaw());
    }
}
