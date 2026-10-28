package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 创建 tus 上传会话命令（{@code POST /kb/documents/tus}，由 TusUploadResource 从 Upload-Metadata 头解析）
 *
 * @param batchId          批次 ID（BATCH_IMPORT 必填，来自 Upload-Metadata.batchId；doc 用途为 null）
 * @param purpose          会话用途（来自 Upload-Metadata.purpose；null=缺省 BATCH_IMPORT；
 *                         DOC_EDIT=文档编辑换文件，VERSION_OVERRIDE=版本覆盖）
 * @param targetDocumentId 目标文档 ID（doc 用途必填，来自 Upload-Metadata.targetDocumentId；BATCH_IMPORT 为 null）
 * @param fileName         文件名（必填，来自 Upload-Metadata.filename）
 * @param mimeType         MIME 类型（可选，来自 Upload-Metadata.mimeType；null 由 complete 推导）
 * @param uploadLength     文件总字节（来自 Upload-Length 头）
 * @param perFileMetadata  逐文件元数据（来自 Upload-Metadata.metadata 的 base64 JSON blob，
 *                         前端复用普通上传 metadata 形态；null/空=继承批次 default_metadata
 *                         ——仅 BATCH_IMPORT；doc 用途 null=仅换文件不改属性）
 * @param draftPolicy      草稿同名冲突策略（可选，来自 Upload-Metadata.draftPolicy 的 base64，
 *                         "OVERRIDE"/"SKIP"；null/空白/非法=继承批次 draftConflictPolicy，默认 OVERRIDE；
 *                         仅 BATCH_IMPORT 消费）
 */
@RegisterForReflection
public record CreateUploadSessionCommand(
        String batchId,
        String purpose,
        String targetDocumentId,
        String fileName,
        String mimeType,
        long uploadLength,
        ImportFileMetadata perFileMetadata,
        String draftPolicy) {

}