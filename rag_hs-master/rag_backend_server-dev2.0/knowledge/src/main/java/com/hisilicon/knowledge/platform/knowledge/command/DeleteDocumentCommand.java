package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 物理删除文档命令（<b>仅版本级</b>）
 * <p>移除 document_versions 指定版本行 + 文件 + 向量。传当前版本号时删除当前版：
 * documents 行一并删除（document_versions 历史行保留），current_version 随之消失。</p>
 *
 * @param documentId 文档 ID（必填）
 * @param version    版本号（必填；当前版=删 documents 行+当前版文件；历史版=仅删版本行）
 */
public record DeleteDocumentCommand(
        @NotBlank(message = "{app.document.id.notblank}")
        String documentId,
        @NotBlank(message = "{app.document.version.notblank}")
        String version) {
}
