package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 版本内容对比命令
 *
 * @param documentId    文档 ID（必填）
 * @param sourceVersion 源版本号（旧版，必填；可为当前版或历史版）
 * @param targetVersion 目标版本号（新版，必填；可与 sourceVersion 相同的校验在服务层做）
 */
public record VersionDiffCommand(
        @NotBlank(message = "{app.document.id.notblank}")
        String documentId,

        @NotBlank(message = "{app.document.version.notblank}")
        String sourceVersion,

        @NotBlank(message = "{app.document.version.notblank}")
        String targetVersion) {
}
