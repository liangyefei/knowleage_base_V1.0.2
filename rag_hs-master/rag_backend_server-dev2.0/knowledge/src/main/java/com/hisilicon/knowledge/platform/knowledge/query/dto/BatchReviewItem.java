package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 批量审核单条结果（SUCCESS / SKIPPED 两种状态）
 * <p>对齐 {@link FileUploadResult} 的 SUCCESS/SKIPPED 范式：
 * <ul>
 *   <li>{@code SUCCESS} —— 文档处于 REVIEWING，已执行通过（→PUBLISHED）或驳回（→DRAFT）</li>
 *   <li>{@code SKIPPED} —— 文档非 REVIEWING 状态或不存在，已跳过（记录原因 + 错误码）</li>
 * </ul>
 */
public class BatchReviewItem {

    /** 结果状态 */
    private String status;          // SUCCESS | SKIPPED

    /** 审核动作 */
    private String action;          // APPROVE | REJECT

    // ===== 成功字段 =====
    private String documentId;
    private String title;
    /** 处理后的生命周期状态（PUBLISHED / DRAFT） */
    private String lifecycleStatus;

    // ===== 跳过字段 =====
    private String reason;
    private String code;            // 错误码

    // ========== 工厂方法 ==========

    public static BatchReviewItem success(String documentId, String title,
                                          String lifecycleStatus, String action) {
        BatchReviewItem r = new BatchReviewItem();
        r.status = "SUCCESS";
        r.action = action;
        r.documentId = documentId;
        r.title = title;
        r.lifecycleStatus = lifecycleStatus;
        return r;
    }

    public static BatchReviewItem skipped(String documentId, String title,
                                          String reason, String code, String action) {
        BatchReviewItem r = new BatchReviewItem();
        r.status = "SKIPPED";
        r.action = action;
        r.documentId = documentId;
        r.title = title;
        r.reason = reason;
        r.code = code;
        return r;
    }

    // ========== getters ==========

    public String getStatus() { return status; }
    public String getAction() { return action; }
    public String getDocumentId() { return documentId; }
    public String getTitle() { return title; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public String getReason() { return reason; }
    public String getCode() { return code; }
}
