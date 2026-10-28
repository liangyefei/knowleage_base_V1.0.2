package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 代码↔文档映射查询入参（POST /kb/doc-code-mapping）。
 *
 * <p>对应前端流程：选方向（t2 的 file-options）→ 选版本（listVersions）→ 发起映射。
 * version 必填：与前端步骤 4 选定的版本对应。</p>
 *
 * @param documentId 文档 ID（必填）
 * @param version    版本号（必填；当前版或历史版，决定传给模型的 file_path）
 * @param direction  映射方向：doc2code（文档→代码）/ code2doc（代码→文档），非法值 A0420
 * @param withSource 命中项附带源码片段（可选，透传模型）
 * @param withDoc    命中项附带文档原文（可选，透传模型）
 * @param limit      返回映射条数上限（可选，缺省由客户端补 20）
 */
public record DocCodeMappingCommand(
        @NotBlank(message = "{app.document.id.notblank}") String documentId,
        @NotBlank(message = "{app.document.version.notblank}") String version,
        @NotBlank(message = "{app.direction.notblank}") String direction,
        Boolean withSource,
        Boolean withDoc,
        Integer limit) {
}
