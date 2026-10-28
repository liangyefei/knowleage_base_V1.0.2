package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 下载文档命令
 *
 * @param documentId 文档 ID（必填）
 * @param version    版本号（可选：缺省=当前版（documents.file_path）；传当前版本号效果相同；
 *                   传历史版本号下载 document_versions 版本行文件，版本行已下线 → A0508）
 */
public record DownloadDocumentCommand(
        @NotBlank(message = "{app.document.id.notblank}")
        String documentId,

        String version) {
}
