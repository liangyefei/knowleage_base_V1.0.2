package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DraftConflictPolicy;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 创建导入批次命令（{@code POST /kb/import/batches} body）
 *
 * @param kbId                知识库 ID（必填）
 * @param categoryId          默认类别 ID（可空=根类别）
 * @param batchName           批次名称（必填）
 * @param defaultMetadata     逐文件元数据默认值（前端 metadata JSON 形态，与普通上传同；服务层映射为 ImportFileMetadata）
 * @param draftConflictPolicy 草稿态同名冲突策略（可空=OVERRIDE）
 */
@RegisterForReflection
public record CreateImportBatchCommand(
        String kbId,
        String categoryId,
        String batchName,
        UploadDocumentCommand defaultMetadata,
        DraftConflictPolicy draftConflictPolicy) {
}
