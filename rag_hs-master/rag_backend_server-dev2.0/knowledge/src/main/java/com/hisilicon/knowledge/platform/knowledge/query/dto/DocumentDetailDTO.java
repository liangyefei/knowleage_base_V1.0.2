package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档详情 DTO
 * <p>文档详情查看接口（{@code GET /kb/documents/detail?documentId=...}）的返回体。
 * 聚合三部分信息：基础信息（含切片数）、知识属性（含标签）、审核历史。</p>
 *
 * <h3>字段来源</h3>
 * <ul>
 *   <li>基础信息：documents 表（title/docType/status/fileSize/author/createdBy/currentVersion/
 *       chunkCount/createdAt/updatedAt）</li>
 *   <li>元数据：document_metadata 表的 meta_key → meta_value（chip_model/sensitivity/product/
 *       official/access_policy 等），与 documents 列组装的 title/author/tag/version 合并</li>
 *   <li>标签：documents.tags 逗号分隔串拆分为 List</li>
 *   <li>审核历史：review_history 表按 created_at 倒序</li>
 * </ul>
 *
 * @param documentId     文档 ID
 * @param title          文档标题
 * @param docType        文档类型分类（DOCUMENT / CODE / CONFIG / OTHER）
 * @param status         生命周期状态（DRAFT / REVIEWING / PUBLISHED / ARCHIVED / DEPRECATED）
 * @param currentVersion 当前版本号
 * @param version        本详情对应的版本号：不带 version 请求=currentVersion；带 version 请求=该历史版本号
 * @param isCurrentVersion 详情是否为当前版本（true=documents 当前信息；false=document_versions 历史版本信息）
 * @param versionStatus  版本行状态：当前版本=文档生命周期状态；历史版本=PUBLISHED/ARCHIVED/DEPRECATED
 * @param fileSize       文件大小（bytes）
 * @param author         文档作者（自由文本）
 * @param createdBy      创建者用户 ID
 * @param createdAt      创建时间（上传时间）
 * @param updatedAt      最后更新时间
 * @param chunkCount     切片总数（模型回调 PROCESSING_COMPLETED 时回传）
 * @param tags           标签列表（documents.tags 逗号分隔串拆分）
 * @param metadata       全部元数据（document_metadata key-value + documents 列组装）
 * @param reviewHistory  审核历史（按时间倒序）
 */
public record DocumentDetailDTO(String documentId, String title, String docType,
                                String status, String currentVersion,
                                String version, boolean isCurrentVersion, String versionStatus,
                                Long fileSize, String author, String createdBy,
                                LocalDateTime createdAt, LocalDateTime updatedAt,
                                Integer chunkCount,
                                List<String> tags,
                                Map<String, String> metadata,
                                List<ReviewHistoryItem> reviewHistory) {
}
