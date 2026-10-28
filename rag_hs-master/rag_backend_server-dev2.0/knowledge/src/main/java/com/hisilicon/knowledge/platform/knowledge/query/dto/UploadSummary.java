package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 上传汇总统计
 */
public class UploadSummary {

    private int total;
    private int success;
    private int skipped;
    private int failed;

    public UploadSummary() {}

    public UploadSummary(int total, int success, int skipped, int failed) {
        this.total = total;
        this.success = success;
        this.skipped = skipped;
        this.failed = failed;
    }

    public int getTotal() { return total; }
    public int getSuccess() { return success; }
    public int getSkipped() { return skipped; }
    public int getFailed() { return failed; }
}
