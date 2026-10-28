package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 文档操作通用命令（携带文档 ID + 可选版本号，用于状态流转类操作）
 *
 * <p>归档/废弃已改为仅版本级操作，使用 {@link DocumentVersionCommand}（version 必填）；
 * 本命令仍用于审核等文档级操作（忽略 version 字段）。</p>
 *
 * @param documentId 文档 ID（必填）
 * @param version    版本号（可选；审核等文档级操作忽略该字段）
 */
public record DocumentIdCommand(
        @NotBlank(message = "{app.document.id.notblank}")
        String documentId,
        String version) {
}
