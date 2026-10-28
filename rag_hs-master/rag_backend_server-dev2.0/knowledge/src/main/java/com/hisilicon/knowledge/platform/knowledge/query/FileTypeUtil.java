package com.hisilicon.knowledge.platform.knowledge.query;

import java.util.Locale;
import java.util.Optional;

/**
 * 文件类型（fileType）推断工具。
 * <p>fileType 的权威源为<b>文件名扩展名</b>（上传时由
 * {@code DocumentCommandService.extractExtension} 算出并写入 {@code file_path}），
 * 而非客户端上报的 {@code mime_type}——后者对 {@code .h}/{@code .hpp}/{@code .ini} 等
 * 无标准 MIME 的扩展名常回退为 {@code text/plain}，导致误显为 {@code txt}。
 * 查询侧统一从 {@code file_path} 反解扩展名，与上传时的 extension 同源、无信息损失。</p>
 */
final class FileTypeUtil {

    private FileTypeUtil() {
    }

    /**
     * 从文件存储路径反解文件类型短名（小写扩展名）。
     * <p>{@code file_path} 形如 {@code {kbId}[/{categoryId}]/{原始文件名}}，
     * 末段即原始文件名（{@code buildStoragePath → sanitizeFileName} 保留扩展名）。
     * 先取末段（兼容 {@code /} 与 {@code \}，避免目录段中的 {@code .} 污染），
     * 再取末段的最后一个 {@code .} 之后部分并小写。无扩展名或路径为空返回空 {@code Optional}。</p>
     *
     * @param filePath 文件存储相对路径（{@code documents.file_path}）
     * @return 小写扩展名（如 {@code "h"}、{@code "pdf"}），无扩展名或入参为空时为空 {@code Optional}
     */
    static Optional<String> fromPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String name = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        if (name.isEmpty()) {
            return Optional.empty();
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
