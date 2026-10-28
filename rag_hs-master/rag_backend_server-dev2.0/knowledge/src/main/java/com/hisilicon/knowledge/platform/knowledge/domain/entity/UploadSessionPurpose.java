package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * tus 上传会话用途（upload_sessions.purpose）
 * <p>同一套 tus 会话设施服务三类场景，create 校验与 complete 落库按用途分流：</p>
 * <ul>
 *   <li>{@code BATCH_IMPORT}：初始化批量导入（既有路径，batchId 必填，complete 走
 *       importCompletedFile / zip 解压）；存量行与缺省用途</li>
 *   <li>{@code DOC_EDIT}：文档编辑换文件（DRAFT 限、同类型强校验），targetDocumentId 必填，
 *       complete 走 editWithFileFromTemp</li>
 *   <li>{@code VERSION_OVERRIDE}：已发布文档版本覆盖（PUBLISHED 限、同类型强校验），
 *       targetDocumentId 必填，complete 走 overrideVersionFromTemp（旧版归档 document_versions）</li>
 * </ul>
 */
public enum UploadSessionPurpose {

    /** 初始化批量导入（既有路径；batchId 必填） */
    BATCH_IMPORT,

    /** 文档编辑换文件（DRAFT 限、同类型；targetDocumentId 必填） */
    DOC_EDIT,

    /** 版本覆盖（PUBLISHED 限、同类型；targetDocumentId 必填） */
    VERSION_OVERRIDE;

    /** batch-import 的显式别名（与缺省等价） */
    public static final String BATCH_IMPORT_ALIAS = "batchImport";
    public static final String DOC_EDIT_VALUE = "docEdit";
    public static final String VERSION_OVERRIDE_VALUE = "versionOverride";

    /**
     * 解析 tus Upload-Metadata.purpose：null/空白/显式 batchImport → BATCH_IMPORT；
     * 大小写不敏感；未知显式值 → IllegalArgumentException（调用方转 A0420 fail-closed——
     * 用途决定 create 校验与 complete 落库行为，静默降级为导入会误落库）。
     */
    public static UploadSessionPurpose fromString(String value) {
        if (value == null || value.isBlank() || BATCH_IMPORT_ALIAS.equalsIgnoreCase(value.trim())) {
            return BATCH_IMPORT;
        }
        String normalized = value.trim();
        if (DOC_EDIT_VALUE.equalsIgnoreCase(normalized)) {
            return DOC_EDIT;
        }
        if (VERSION_OVERRIDE_VALUE.equalsIgnoreCase(normalized)) {
            return VERSION_OVERRIDE;
        }
        throw new IllegalArgumentException("未知上传用途: " + value);
    }
}
