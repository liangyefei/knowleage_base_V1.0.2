package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import java.time.LocalDateTime;

/**
 * 文档版本归档记录（值对象）
 * <p>版本覆盖/发布时，将文档当前版本的信息归档到 document_versions 表。</p>
 *
 * @param documentId  文档 ID
 * @param versionNumber 版本号
 * @param filePath    文件存储路径
 * @param fileSize    文件大小（bytes）
 * @param mimeType    文件 MIME 类型
 * @param wasPublished 该版本是否曾经审核发布过
 * @param status      版本行状态：PUBLISHED-已发布在线；ARCHIVED-已归档（版本级下线）；DEPRECATED-已废弃
 * @param createdBy   操作人
 * @param createdAt   归档时间
 */
public record DocumentVersion(String documentId, String versionNumber, String filePath,
                              Long fileSize, String mimeType, Boolean wasPublished,
                              String status, String createdBy, LocalDateTime createdAt) {

    /** 版本行状态：已发布在线（原 ACTIVE，2026-09 语义对齐前端显示） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 版本行状态：已归档（版本级下线） */
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    /** 版本行状态：已废弃（版本级下线） */
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    /** 版本覆盖/发布归档工厂参数对象（8 项字段收敛为单一入参；新归档版本恒为 PUBLISHED） */
    public record PublishedSeed(String documentId, String versionNumber, String filePath,
                                Long fileSize, String mimeType, Boolean wasPublished,
                                String createdBy, LocalDateTime createdAt) {
    }

    /** 版本覆盖/发布归档工厂：新归档版本恒为 PUBLISHED */
    public static DocumentVersion published(PublishedSeed seed) {
        return new DocumentVersion(seed.documentId(), seed.versionNumber(), seed.filePath(),
                seed.fileSize(), seed.mimeType(), seed.wasPublished(), STATUS_PUBLISHED,
                seed.createdBy(), seed.createdAt());
    }
}
