package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 批量审核响应（含逐条结果 + 汇总统计）
 * <p>对齐 {@link UploadDocumentResponse} 范式。</p>
 */
public class BatchReviewResponse {

    private List<BatchReviewItem> results;
    private BatchReviewSummary summary;

    public BatchReviewResponse() {}

    public BatchReviewResponse(List<BatchReviewItem> results, BatchReviewSummary summary) {
        this.results = results;
        this.summary = summary;
    }

    public List<BatchReviewItem> getResults() { return results; }
    public BatchReviewSummary getSummary() { return summary; }
}
