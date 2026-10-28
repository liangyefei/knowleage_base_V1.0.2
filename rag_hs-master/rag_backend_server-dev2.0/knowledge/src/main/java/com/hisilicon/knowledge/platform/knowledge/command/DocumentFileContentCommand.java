package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 文件内容查看命令（按知识库 + 模型侧项目内相对路径取文件内容）
 *
 * <p>与 {@link DownloadDocumentCommand} 的区别：download 以 documentId（+版本号）定位文档，
 * 本命令以「知识库 ID + 模型侧给出的项目内相对路径」定位文件——模型侧（代码图谱等）只提供
 * {@code hi_aiot_solution/sdk/…/osal_debug.c} 这类项目内相对路径，不带平台 documentId。</p>
 *
 * @param kbId 知识库 ID（必填）：用于解析该库的物理目录段，落盘路径 = {@code kb/{KB段}/{path}}
 * @param path 模型侧项目内相对路径（必填），如
 *             {@code hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/kernel/osal_debug.c}；
 *             容错接受带前导 {@code /} 或已是存储相对路径 {@code kb/{KB段}/…} 的形态
 */
public record DocumentFileContentCommand(
        @NotBlank(message = "{app.kb.id.notblank}")
        String kbId,

        @NotBlank(message = "{app.document.path.notblank}")
        String path) {
}
