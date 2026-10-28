package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 批量删除汇总统计
 * <p>对齐 {@link BatchSubmitReviewSummary} 范式。total 为去重后的请求数，
 * deleted + skipped + failed = total。</p>
 */
public class BatchDeleteSummary {

    private int total;
    private int deleted;
    private int skipped;
    private int failed;

    public BatchDeleteSummary() {}

    public BatchDeleteSummary(int total, int deleted, int skipped, int failed) {
        this.total = total;
        this.deleted = deleted;
        this.skipped = skipped;
        this.failed = failed;
    }

    public int getTotal() { return total; }
    public int getDeleted() { return deleted; }
    public int getSkipped() { return skipped; }
    public int getFailed() { return failed; }
}
