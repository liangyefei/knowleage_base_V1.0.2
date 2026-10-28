package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文档列表项 DTO
 * <p>字段与编辑/上传/查看接口对齐：搜索结果可直接回填编辑表单，与
 * {@link DocumentMetadataView} 的可编辑字段（title/author/remark/currentVersion/metadata）形态一致。
 * 标签不再平铺，统一由 {@code metadata.tag}（逗号串，同 documents.tags 列）承载，与上传一致。</p>
 *
 * @param id             文档 ID
 * @param title          文档标题（对齐 {@code EditDocumentCommand.title} / {@link DocumentMetadataView#title()})
 * @param fileType       文件类型（从 file_path 反解扩展名，如 "pdf"/"h"；无扩展名或缺失为 null）
 * @param docType        文档类型分类（DOCUMENT/CODE/CONFIG/OTHER）
 * @param size           文件大小（bytes）
 * @param status         文档生命周期状态（DRAFT/REVIEWING/PUBLISHED 等原始值）
 * @param processStatus  模型处理状态（PENDING/PROCESSING/COMPLETED/FAILED）
 * @param createdBy      创建者
 * @param createdAt      创建时间
 * @param author         文档作者（自由文本，可空；亦同步放入 metadata.author）
 * @param remark         文档备注（自由文本，可空；亦同步放入 metadata.remark）
 * @param currentVersion 当前版本号（documents.current_version 列承载；亦同步放入 metadata.version；可空）
 * @param version        本行版本号：版本级结果行（版本覆盖问题 4 终版语义）——当前版行=currentVersion，
 *                       历史版行=document_versions.version_number；前端按 (id, version) 下载对应版本
 * @param isCurrentVersion 本行是否当前版本（true=documents 当前路径；false=归档历史版本）
 * @param versionStatus  版本行状态：当前版行=文档生命周期（DRAFT/REVIEWING/PUBLISHED/ARCHIVED/DEPRECATED）；
 *                       历史版行=PUBLISHED/ARCHIVED/DEPRECATED。
 *                       可见性由现有 statuses 入参决定（ARCHIVED 行需 statuses 含 ARCHIVED 或不限）
 * @param metadata       文档元数据（含上传 UploadDocumentCommand 全部项）：document_metadata 的
 *                       meta_key → meta_value（product/chip_model/sensitivity/official/access_policy 等）
 *                       + 从 documents 列组装的 title/author/tag(逗号串)/remark/version(=本行版本号)；
 *                       key 名对齐上传 JSON 名；无元数据时为空 Map
 * @param official       官方标识（document_metadata.official 原值：OFFICIAL/NON_OFFICIAL；无该元数据行
 *                       → null，即「未标注」，序列化为 {@code "official": null}）。<b>文档级属性</b>：
 *                       同一文档的当前版行与历史版行取同一值（与 metadata 里 product/chip_model 一致）。
 *                       仍同步存在于 {@code metadata.official}；不向 metadata 注入 null 占位，保持其既有形状。
 *                       追加在 record 末尾（位置参数构造调用点全量显式传参，末尾追加避免错位）
 */
public record DocumentListItem(String id, String title, String fileType, String docType,
                               Long size, String status, String processStatus,
                               String createdBy, LocalDateTime createdAt,
                               String author, String remark,
                               String currentVersion, String version, boolean isCurrentVersion,
                               String versionStatus,
                               Map<String, String> metadata,
                               String official) {
}
