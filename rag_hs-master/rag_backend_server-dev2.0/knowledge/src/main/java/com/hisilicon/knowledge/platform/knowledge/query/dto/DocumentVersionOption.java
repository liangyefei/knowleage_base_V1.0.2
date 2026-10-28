package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * 文档版本选项（版本对比步骤 3：版本选择下拉）
 * <p>当前版取 documents 行（isCurrent=true）；历史版取 document_versions 版本行（createdAt 倒序）。
 * 已下线版本（status=ARCHIVED/DEPRECATED）仍返回，前端可显示"已下线"标签并自行决定是否可选——
 * 对比历史正是本功能的场景。</p>
 *
 * @param version      版本号
 * @param isCurrent    是否当前版（documents.current_version）
 * @param status       版本行状态（当前版=文档生命周期状态；历史版=PUBLISHED/ARCHIVED/DEPRECATED）
 * @param wasPublished 该版本是否曾经审核发布过（历史版行才有意义）
 * @param fileSize     文件大小（bytes）
 * @param mimeType     MIME 类型
 * @param changeSummary 变更说明（历史版行的归档说明；当前版为 null）
 * @param createdBy    上传/归档人用户 ID
 * @param createdAt    上传/归档时间
 */
public record DocumentVersionOption(
        String version,
        boolean isCurrent,
        String status,
        Boolean wasPublished,
        Long fileSize,
        String mimeType,
        String changeSummary,
        String createdBy,
        LocalDateTime createdAt) {
}
