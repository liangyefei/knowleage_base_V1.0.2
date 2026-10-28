package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词导入 CSV 工具（commons-csv，RFC4180 引号转义正确，纯 Java native 安全）
 *
 * <p>与 {@link SensitiveWordExcel} 同构的 5 列格式：标签名称|标签标识|标签是否启用|敏感词|词是否启用。
 * UTF-8（BOM 可选），首行表头必须精确一致（不做宽容跳过——多列格式宽容跳过有歧义）。</p>
 */
public final class SensitiveWordCsv {

    private static final char BOM = '\uFEFF';

    private SensitiveWordCsv() {
        throw new IllegalStateException("工具类不允许实例化");
    }

    /**
     * 解析导入 CSV：首行表头必须与 {@link SensitiveWordExcel#HEADERS} 精确一致，
     * 数据行全空跳过
     *
     * @param content csv 文件字节（UTF-8，BOM 可选）
     * @return 原始行列表（保持文件顺序）
     * @throws IOException 解析失败（格式错误/表头不匹配）
     */
    public static List<SensitiveWordImportRow> parseRows(byte[] content) throws IOException {
        List<SensitiveWordImportRow> rows = new ArrayList<>();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            boolean firstRecord = true;
            for (CSVRecord record : parser) {
                if (record == null || record.size() == 0) {
                    continue;
                }
                if (firstRecord) {
                    firstRecord = false;
                    // 去掉 UTF-8 BOM 后与表头精确比对
                    String first = stripBom(record.get(0)).trim();
                    if (!SensitiveWordExcel.HEADERS[0].equals(first)) {
                        throw new IOException("表头不匹配：期望 " + String.join("|", SensitiveWordExcel.HEADERS)
                                + "，实际首列为 " + first);
                    }
                    for (int i = 1; i < SensitiveWordExcel.HEADERS.length; i++) {
                        String cell = i < record.size() ? record.get(i).trim() : null;
                        if (!SensitiveWordExcel.HEADERS[i].equals(cell)) {
                            throw new IOException("表头不匹配：期望 " + String.join("|", SensitiveWordExcel.HEADERS)
                                    + "，实际第 " + (i + 1) + " 列为 " + cell);
                        }
                    }
                    continue;
                }
                String[] cells = new String[SensitiveWordExcel.HEADERS.length];
                boolean blank = true;
                for (int c = 0; c < SensitiveWordExcel.HEADERS.length; c++) {
                    if (c >= record.size()) {
                        break;
                    }
                    String value = record.get(c);
                    value = value == null ? null : value.trim();
                    cells[c] = value == null || value.isEmpty() ? null : value;
                    if (cells[c] != null) {
                        blank = false;
                    }
                }
                if (blank) {
                    continue;
                }
                rows.add(new SensitiveWordImportRow(cells[0], cells[1], cells[2], cells[3], cells[4]));
            }
        }
        return rows;
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == BOM) {
            return value.substring(1);
        }
        return value == null ? "" : value;
    }
}
