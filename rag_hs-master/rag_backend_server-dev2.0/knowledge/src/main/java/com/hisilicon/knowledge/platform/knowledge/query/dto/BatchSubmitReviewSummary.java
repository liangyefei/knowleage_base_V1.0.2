package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 批量提交审核汇总统计
 * <p>对齐 {@link BatchReviewSummary} 范式。remainingCount：KB/CATEGORY/DOCUMENT 范围超过
 * {@code BATCH_REVIEW_MAX_PER_RUN}（1000）时未审核的剩余条数。</p>
 */
public class BatchSubmitReviewSummary {

    private int total;
    private int success;
    private int skipped;
    private int remainingCount;

    public BatchSubmitReviewSummary() {}

    public BatchSubmitReviewSummary(int total, int success, int skipped, int remainingCount) {
        this.total = total;
        this.success = success;
        this.skipped = skipped;
        this.remainingCount = remainingCount;
    }

    public int getTotal() { return total; }
    public int getSuccess() { return success; }
    public int getSkipped() { return skipped; }
    public int getRemainingCount() { return remainingCount; }
}
