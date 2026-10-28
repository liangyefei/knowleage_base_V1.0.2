package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

/**
 * 压缩包格式判定（按扩展名）。
 * <p>统一 zip / rar 的分岔判定入口，替代调用方各自 endsWith(".zip") 的散点；
 * 判定只看扩展名（与上传白名单口径一致），魔数校验由各 extractor 在解压入口负责。</p>
 */
public final class ArchiveFormats {

    private ArchiveFormats() {
    }

    /** 是否为 ZIP 压缩包（.zip） */
    public static boolean isZip(String fileName) {
        return matchesExtension(fileName, ".zip");
    }

    /** 是否为 RAR 压缩包（.rar，含分卷 .partN.rar） */
    public static boolean isRar(String fileName) {
        return matchesExtension(fileName, ".rar");
    }

    /** 是否为受支持的压缩包（zip / rar） */
    public static boolean isArchive(String fileName) {
        return isZip(fileName) || isRar(fileName);
    }

    private static boolean matchesExtension(String fileName, String extension) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(extension);
    }
}
