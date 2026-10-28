package com.hisilicon.knowledge.platform.commons.dict.chat;

/**
 * 对话 docs 出处解析结果项：路径 → 文档引用。
 *
 * @param documentId    文档 id（documents.id）
 * @param version       命中版本号：documents 当前路径命中=current_version；
 *                      document_versions 历史路径兜底命中=version_number；理论不为空
 * @param versionStatus 版本行状态：documents 当前路径命中=文档生命周期（DRAFT/REVIEWING/PUBLISHED/
 *                      ARCHIVED/DEPRECATED）；历史路径命中=PUBLISHED/ARCHIVED/DEPRECATED
 *                      （下线版本前端提示「文档已归档/已废弃」，下载会被 A0508 拦截）
 */
public record ChatDocRef(String documentId, String version, String versionStatus) {

    /** 版本行状态：已发布在线（原 ACTIVE，2026-09 语义对齐前端显示） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 版本行状态：已归档 */
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    /** 版本行状态：已废弃 */
    public static final String STATUS_DEPRECATED = "DEPRECATED";
}
