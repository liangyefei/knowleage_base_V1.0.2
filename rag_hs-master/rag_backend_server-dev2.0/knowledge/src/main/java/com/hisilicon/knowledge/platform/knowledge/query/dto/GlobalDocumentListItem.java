package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文档全局搜索列表项 DTO
 * <p>在 {@link DocumentListItem} 基础上增加"出处"信息（kbId/kbName/categoryId/categoryName），
 * 用于跨知识库检索结果定位文档所属。字段形态与 {@code DocumentListItem} 对齐：
 * 标签由 {@code metadata.tag}（逗号串）承载，{@code author}/{@code remark}/{@code version}
 * 亦同步放入 metadata，前端可一处取齐上传项。</p>
 *
 * @param id             文档 ID
 * @param kbId           所属知识库 ID
 * @param kbName         所属知识库名称（KB 已删或缺失时为 null）
 * @param categoryId     所属类别 ID（null=知识库根目录文档）
 * @param categoryName   所属类别名称（根目录或类别缺失时为 null）
 * @param title          文档标题
 * @param fileType       文件类型（从 file_path 反解扩展名，如 "pdf"/"h"；无扩展名或缺失为 null）
 * @param docType        文档类型分类（DOCUMENT/CODE/CONFIG/OTHER）
 * @param size           文件大小（bytes）
 * @param status         文档生命周期状态（DRAFT/REVIEWING/PUBLISHED 等原始值）
 * @param processStatus  模型处理状态（PENDING/PROCESSING/COMPLETED/FAILED）
 * @param createdBy      创建者
 * @param createdAt      创建时间
 * @param author         文档作者（自由文本，可空；亦同步放入 metadata.author）
 * @param remark         文档备注（自由文本，可空；亦同步放入 metadata.remark）
 * @param currentVersion 当前版本号（亦同步放入 metadata.version；可空）
 * @param metadata       文档元数据（含上传全部项）：document_metadata 的 meta_key → meta_value
 *                       + 从 documents 列组装的 title/author/tag/remark/version；无元数据时为空 Map
 * @param content        文档内容预览（关键词片段，首个命中位置前后各 ~100 字符）。
 *                       仅内容命中（searchField=all/content 且抽取文本 READY、filePath 与最新版等值）
 *                       时返回；title 命中或无片段为 {@code null}
 */
public record GlobalDocumentListItem(String id, String kbId, String kbName,
                                     String categoryId, String categoryName,
                                     String title, String fileType, String docType,
                                     Long size, String status, String processStatus,
                                     String createdBy, LocalDateTime createdAt,
                                     String author, String remark,
                                     String currentVersion, Map<String, String> metadata,
                                     String content, LocalDateTime updatedAt) {
}
