package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 文档版本级操作命令（文档 ID + 必填版本号）
 *
 * <p>归档/废弃仅支持版本级操作：对 document_versions 中指定版本行下线
 * （ARCHIVED/DEPRECATED），文档生命周期不变；{@code version} 必填，
 * 不允许对整个文档（所有版本/当前版）执行归档/废弃。</p>
 *
 * @param documentId 文档 ID（必填）
 * @param version    版本号（必填；指向 document_versions 中的历史版本行）
 */
public record DocumentVersionCommand(
        @NotBlank(message = "{app.document.id.notblank}")
        String documentId,
        @NotBlank(message = "{app.document.version.notblank}")
        String version) {
}
