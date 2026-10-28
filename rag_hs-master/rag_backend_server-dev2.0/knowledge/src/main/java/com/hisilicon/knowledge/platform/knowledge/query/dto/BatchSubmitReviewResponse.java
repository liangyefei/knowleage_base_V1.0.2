package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 批量提交审核响应（含逐条结果 + 汇总统计）
 * <p>对齐 {@link BatchReviewResponse} 范式。</p>
 */
public class BatchSubmitReviewResponse {

    private List<BatchSubmitReviewItem> results;
    private BatchSubmitReviewSummary summary;

    public BatchSubmitReviewResponse() {}

    public BatchSubmitReviewResponse(List<BatchSubmitReviewItem> results, BatchSubmitReviewSummary summary) {
        this.results = results;
        this.summary = summary;
    }

    public List<BatchSubmitReviewItem> getResults() { return results; }
    public BatchSubmitReviewSummary getSummary() { return summary; }
}
