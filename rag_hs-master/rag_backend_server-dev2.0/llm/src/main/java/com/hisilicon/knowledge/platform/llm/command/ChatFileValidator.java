package com.hisilicon.knowledge.platform.llm.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.llm.exception.ConversationFileInvalidException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 会话问答文件校验工具
 * <p>扩展名白名单与知识库文档上传一致（不支持压缩包），大小上限可配置。</p>
 */
public final class ChatFileValidator {

    /** 允许的文件扩展名（小写，与知识库文档上传白名单一致，不含压缩包格式） */
    // 支持的文件类型清单见 docs/supported_file_formats.json，与 DocumentCommandService.ALLOWED_EXTENSIONS 一致。
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 文档：pdf/word/excel/ppt/markdown/txt/html/csv
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "txt", "html", "htm", "csv",
            // 代码：c/c++/python/typescript/arkts/java/javascript
            "c", "cc", "cpp", "cxx", "h", "hpp", "py", "ts", "tsx", "ets", "java", "js", "mjs", "cjs",
            // 配置：ini/dtsi/xml/sh/makefile(.cmake)
            "ini", "dts", "dtsi", "dtso", "xml", "sh", "cmake"
    );

    /** 允许的无扩展名文件名（小写精确匹配） */
    public static final Set<String> ALLOWED_FILENAMES = Set.of("makefile");

    private ChatFileValidator() {
    }

    /**
     * 校验文件大小与类型
     *
     * @param fileName    原始文件名
     * @param content     文件内容
     * @param maxFileSize 大小上限（bytes）
     * @param locale      语言环境（错误消息国际化）
     * @throws ConversationFileInvalidException 校验失败（A0704 空文件 / A0705 过大 / A0701 类型不支持）
     */
    public static void validate(String fileName, byte[] content, long maxFileSize, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        if (content == null || content.length == 0) {
            throw new ConversationFileInvalidException(ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME,
                    appMessages.fileCannotEmpty());
        }
        if (content.length > maxFileSize) {
            throw new ConversationFileInvalidException(ErrorCodeEnum.USER_ERROR_A0705,
                    "文件大小超过限制（最大 " + formatBytes(maxFileSize) + "）");
        }
        Optional<String> ext = extensionOf(fileName);
        boolean allowed = (ext.isPresent() && ALLOWED_EXTENSIONS.contains(ext.get()))
                || (ext.isEmpty() && ALLOWED_FILENAMES.contains(fileName.toLowerCase(Locale.ROOT)));
        if (!allowed) {
            throw new ConversationFileInvalidException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    "不支持的文件类型: " + fileName);
        }
    }

    /**
     * 提取小写扩展名（无扩展名为 empty）
     */
    public static Optional<String> extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(fileName.substring(idx + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 按扩展名归类文件类型：DOCUMENT / CODE / CONFIG / OTHER
     */
    // 分组对齐 docs/supported_file_formats.json
    public static String deduceFileType(String fileName) {
        Optional<String> ext = extensionOf(fileName);
        if (ext.isEmpty()) {
            // 无扩展名构建脚本（Makefile）→ CONFIG；CMakeLists.txt 走 .txt→DOCUMENT；其余无扩展名 → OTHER
            String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if ("makefile".equals(lower)) {
                return "CONFIG";
            }
            return "OTHER";
        }
        String e = ext.get();
        if (Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "txt", "html", "htm", "csv").contains(e)) {
            return "DOCUMENT";
        }
        if (Set.of("c", "cc", "cpp", "cxx", "h", "hpp", "py", "ts", "tsx", "ets", "java", "js", "mjs", "cjs").contains(e)) {
            return "CODE";
        }
        if (Set.of("ini", "dts", "dtsi", "dtso", "xml", "sh", "cmake").contains(e)) {
            return "CONFIG";
        }
        return "OTHER";
    }

    /** 便于整数精确计算的字节换算基数（无浮点误差，仅用于展示格式化） */
    private static final BigDecimal KB = BigDecimal.valueOf(1024);
    private static final BigDecimal MB = KB.multiply(KB);
    private static final BigDecimal GB = MB.multiply(KB);

    private static String formatBytes(long bytes) {
        BigDecimal size = BigDecimal.valueOf(bytes);
        if (size.compareTo(GB) >= 0) {
            return size.divide(GB, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "G";
        }
        if (size.compareTo(MB) >= 0) {
            return size.divide(MB, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "M";
        }
        return bytes + "B";
    }
}
