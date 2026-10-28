package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 批量删除单条结果（DELETED / SKIPPED / FAILED 三种状态）
 * <p>对齐 {@link BatchSubmitReviewItem} 范式：
 * <ul>
 *   <li>{@code DELETED} —— 文档物理删除成功（仅 DRAFT/DEPRECATED 可删，两阶段文件回收）</li>
 *   <li>{@code SKIPPED} —— 前置条件不满足跳过：文档不存在（A0430）或状态不可删（A0508），
 *       与批量提交审核的 SKIPPED 容忍语义一致（记录原因 + 错误码）</li>
 *   <li>{@code FAILED} —— 单条执行异常（系统/IO 失败，B0001；不影响其余文档）</li>
 * </ul>
 * DELETED 时 {@code reason}/{@code code} 为 null；SKIPPED/FAILED 时填充。</p>
 */
public class BatchDeleteItem {

    /** 结果状态：删除成功 */
    public static final String STATUS_DELETED = "DELETED";

    /** 结果状态：前置条件不满足跳过（不存在/状态不可删） */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** 结果状态：单条执行异常 */
    public static final String STATUS_FAILED = "FAILED";

    /** 结果状态：DELETED | SKIPPED | FAILED */
    private String status;

    private String documentId;
    /** 操作对象版本号（版本级删除时非空；文档级删除为 null） */
    private String version;
    /** 结果原因（DELETED 为 null） */
    private String reason;
    /** 错误码（DELETED 为 null） */
    private String code;

    // ========== 工厂方法 ==========

    public static BatchDeleteItem deleted(String documentId) {
        return deleted(documentId, null);
    }

    public static BatchDeleteItem deleted(String documentId, String version) {
        BatchDeleteItem r = new BatchDeleteItem();
        r.status = STATUS_DELETED;
        r.documentId = documentId;
        r.version = version;
        return r;
    }

    public static BatchDeleteItem skipped(String documentId, String reason, String code) {
        return skipped(documentId, null, reason, code);
    }

    public static BatchDeleteItem skipped(String documentId, String version, String reason, String code) {
        BatchDeleteItem r = new BatchDeleteItem();
        r.status = STATUS_SKIPPED;
        r.documentId = documentId;
        r.version = version;
        r.reason = reason;
        r.code = code;
        return r;
    }

    public static BatchDeleteItem failed(String documentId, String reason, String code) {
        return failed(documentId, null, reason, code);
    }

    public static BatchDeleteItem failed(String documentId, String version, String reason, String code) {
        BatchDeleteItem r = new BatchDeleteItem();
        r.status = STATUS_FAILED;
        r.documentId = documentId;
        r.version = version;
        r.reason = reason;
        r.code = code;
        return r;
    }

    // ========== getters ==========

    public String getStatus() { return status; }
    public String getDocumentId() { return documentId; }
    public String getVersion() { return version; }
    public String getReason() { return reason; }
    public String getCode() { return code; }
}
