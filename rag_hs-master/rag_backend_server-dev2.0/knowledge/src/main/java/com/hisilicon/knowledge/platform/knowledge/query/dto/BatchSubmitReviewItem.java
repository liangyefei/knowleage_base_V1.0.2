package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 批量提交审核单条结果（SUCCESS / SKIPPED 两种状态）
 * <p>对齐 {@link BatchReviewItem} 的 SUCCESS/SKIPPED 范式，但无 {@code action} 字段
 * （提交审核是单一动作，无需区分 APPROVE/REJECT）：
 * <ul>
 *   <li>{@code SUCCESS} —— 文档处于 DRAFT，已执行提交审核（→REVIEWING）</li>
 *   <li>{@code SKIPPED} —— 文档非 DRAFT 状态或不存在，已跳过（记录原因 + 错误码）</li>
 * </ul>
 */
public class BatchSubmitReviewItem {

    /** 结果状态 */
    private String status;          // SUCCESS | SKIPPED

    // ===== 成功字段 =====
    private String documentId;
    private String title;
    /** 处理后的生命周期状态（REVIEWING） */
    private String lifecycleStatus;

    // ===== 跳过字段 =====
    private String reason;
    private String code;            // 错误码

    // ========== 工厂方法 ==========

    public static BatchSubmitReviewItem success(String documentId, String title,
                                                String lifecycleStatus) {
        BatchSubmitReviewItem r = new BatchSubmitReviewItem();
        r.status = "SUCCESS";
        r.documentId = documentId;
        r.title = title;
        r.lifecycleStatus = lifecycleStatus;
        return r;
    }

    public static BatchSubmitReviewItem skipped(String documentId, String title,
                                               String reason, String code) {
        BatchSubmitReviewItem r = new BatchSubmitReviewItem();
        r.status = "SKIPPED";
        r.documentId = documentId;
        r.title = title;
        r.reason = reason;
        r.code = code;
        return r;
    }

    // ========== getters ==========

    public String getStatus() { return status; }
    public String getDocumentId() { return documentId; }
    public String getTitle() { return title; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public String getReason() { return reason; }
    public String getCode() { return code; }
}
