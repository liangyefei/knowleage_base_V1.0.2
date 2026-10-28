package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词导入/导出 xlsx 工具（POI，经 quarkus-poi 扩展获得 native 支持）
 *
 * <p>格式（导入导出同构，导出文件可直接回导）：
 * 第一个 sheet 共 5 列——标签名称 | 标签标识 | 标签是否启用 | 敏感词 | 词是否启用，
 * 首行表头必须精确一致；模板额外附"填写说明" sheet（仅模板有，不参与解析）。
 * 运行时生成模板（非 classpath 二进制资源）：说明与代码同源，且二进制格式
 * 天然规避 CSV 下载链路的编码猜测乱码问题。</p>
 */
public final class SensitiveWordExcel {

    /** 表头（5 列，顺序即解析顺序） */
    public static final String[] HEADERS = {"标签名称", "标签标识", "标签是否启用", "敏感词", "词是否启用"};

    /** 导入数据 sheet 名（模板） */
    private static final String IMPORT_SHEET = "敏感词导入";
    /** 导出 sheet 名 */
    private static final String EXPORT_SHEET = "敏感词导出";
    /** 模板说明 sheet 名 */
    private static final String GUIDE_SHEET = "填写说明";

    /** 模板填写说明文案（每元素一行） */
    private static final String[] GUIDE_LINES = {
            "敏感词导入模板填写说明",
            "",
            "一、列说明",
            "1. 标签名称（必填）：敏感词分类名称；已存在（未删除）的同名标签会被复用，不存在则新建。",
            "2. 标签标识（可空）：留空默认取标签名称；匹配到已存在标签时本列忽略。",
            "3. 标签是否启用（可空）：启用 / 停用，默认 启用；已存在标签的状态将更新为该值。",
            "4. 敏感词（必填）：一行一词；同一标签内重复仅首次生效；库内已存在且状态相同的词自动跳过。",
            "5. 词是否启用（可空）：启用 / 停用，默认 启用；库内已存在的词状态不同时更新为该值。",
            "",
            "二、规则",
            "1. 一次可导入多个分类：同名标签的多行自动合并为一组，两个启用列均以最后一次出现为准。",
            "2. 仅\"标签启用 且 词启用\"的词参与敏感词拦截，任一停用即不拦截。",
            "3. 请勿修改首行表头；仅支持 .xlsx 格式。",
            "4. 本说明 sheet 不参与导入解析，导入仅读取第一个 sheet 的数据行。",
    };

    /** 列宽（单位 1/256 字符宽） */
    private static final int[] COLUMN_WIDTHS = {24 * 256, 24 * 256, 14 * 256, 40 * 256, 14 * 256};

    private SensitiveWordExcel() {
        throw new IllegalStateException("工具类不允许实例化");
    }

    /**
     * 运行时生成导入模板：Sheet1"敏感词导入"（表头）+ Sheet2"填写说明"
     *
     * @return xlsx 文件字节
     */
    public static byte[] buildTemplate() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = buildHeaderStyle(wb);

            Sheet sheet = wb.createSheet(IMPORT_SHEET);
            sheet.createFreezePane(0, 1);
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i]);
            }

            Sheet guide = wb.createSheet(GUIDE_SHEET);
            for (int i = 0; i < GUIDE_LINES.length; i++) {
                guide.createRow(i).createCell(0).setCellValue(GUIDE_LINES[i]);
            }
            guide.setColumnWidth(0, 110 * 256);

            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 解析导入文件（xlsx）：仅读第一个 sheet，首行表头必须与 {@link #HEADERS} 精确一致，
     * 数据行全空跳过；单元格按显示文本读取（数字列自动转字符串）
     *
     * @param content xlsx 文件字节
     * @return 原始行列表（保持文件顺序）
     * @throws IOException 文件损坏/加密/表头不匹配
     */
    public static List<SensitiveWordImportRow> parseRows(byte[] content) throws IOException {
        try (InputStream in = new ByteArrayInputStream(content); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            int firstRow = sheet.getFirstRowNum();
            Row header = sheet.getRow(firstRow);
            if (header == null) {
                throw new IOException("模板缺少表头行");
            }
            for (int i = 0; i < HEADERS.length; i++) {
                String cell = readCell(header, i);
                if (!HEADERS[i].equals(cell)) {
                    throw new IOException("表头不匹配：期望 " + String.join("|", HEADERS)
                            + "，实际第 " + (i + 1) + " 列为 " + cell);
                }
            }

            List<SensitiveWordImportRow> rows = new ArrayList<>();
            for (int r = firstRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String[] cells = new String[HEADERS.length];
                boolean blank = true;
                for (int c = 0; c < HEADERS.length; c++) {
                    cells[c] = readCell(row, c);
                    if (cells[c] != null) {
                        blank = false;
                    }
                }
                if (blank) {
                    continue;
                }
                rows.add(new SensitiveWordImportRow(cells[0], cells[1], cells[2], cells[3], cells[4]));
            }
            return rows;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // POI 对损坏/伪装文件抛运行时异常（NotOfficeXmlFileException 等），
            // 统一包装为 IOException，由命令层转 A0400 用户错误而非 500
            throw new IOException("文件不是有效的 xlsx 格式: " + e.getMessage(), e);
        }
    }

    /**
     * 生成导出 xlsx（Sheet1"敏感词导出"，5 列表头 + 数据行）
     *
     * @param rows 导出行
     * @return xlsx 文件字节
     */
    public static byte[] writeExport(List<SensitiveWordExportRow> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(EXPORT_SHEET);
            CellStyle headerStyle = buildHeaderStyle(wb);
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i]);
            }
            if (rows != null) {
                int r = 1;
                for (SensitiveWordExportRow row : rows) {
                    Row data = sheet.createRow(r++);
                    data.createCell(0).setCellValue(nullToEmpty(row.textLabel()));
                    data.createCell(1).setCellValue(nullToEmpty(row.labelIdentification()));
                    data.createCell(2).setCellValue(enableText(row.tagStatus()));
                    data.createCell(3).setCellValue(nullToEmpty(row.word()));
                    data.createCell(4).setCellValue(enableText(row.wordStatus()));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ========== 内部实现 ==========

    /**
     * 读单元格显示文本（trim；空白返回 null）。
     * <p>不使用 POI {@code DataFormatter}：static final 实例会在 native 构建时进入 image
     * heap，而其内部依赖运行期才初始化的 {@code java.beans.PropertyChangeSupport}，两者
     * 冲突直接 fatal（本次镜像构建失败根因）。改为按单元格类型手工转文本，语义与显示值
     * 一致：整数值不带小数点（12345 而非 12345.0），公式单元格取缓存结果。</p>
     */
    private static String readCell(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> text(cell.getStringCellValue());
            case NUMERIC -> numericToText(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> formulaText(cell);
            default -> null; // BLANK / _NONE / ERROR
        };
    }

    /**
     * 公式单元格取缓存结果（模板导入不写公式，防御用户粘贴带公式的单元格）
     */
    private static String formulaText(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> text(cell.getStringCellValue());
            case NUMERIC -> numericToText(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    /**
     * 数值转文本：整数值不带小数点（12345 而非 12345.0），与 Excel 常规显示一致；
     * 超出 long 精度范围（±2^53，double 整数精确表示上限）按原样转字符串
     */
    private static String numericToText(double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value) && value == Math.floor(value)
                && Math.abs(value) <= 9_007_199_254_740_992L) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String text(String value) {
        value = value == null ? null : value.trim();
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 状态值转启用文案（1=启用，其余=停用）
     */
    private static String enableText(Short status) {
        return status != null && status == 1 ? "启用" : "停用";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static CellStyle buildHeaderStyle(XSSFWorkbook wb) {
        Font bold = wb.createFont();
        bold.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
