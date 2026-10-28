package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 批量审核汇总统计
 * <p>对齐 {@link UploadSummary} 范式。remainingCount：KB/CATEGORY/DOCUMENT 范围超过
 * {@code BATCH_REVIEW_MAX_PER_RUN}（1000）时未审核的剩余条数，前端可提示用户再次提交。</p>
 */
public class BatchReviewSummary {

    private int total;
    private int success;
    private int skipped;
    private int remainingCount;

    public BatchReviewSummary() {}

    public BatchReviewSummary(int total, int success, int skipped, int remainingCount) {
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
