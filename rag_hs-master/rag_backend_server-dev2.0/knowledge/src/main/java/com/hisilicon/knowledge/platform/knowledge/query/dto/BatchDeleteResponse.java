package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 批量删除响应（含逐条结果 + 汇总统计）
 * <p>对齐 {@link BatchSubmitReviewResponse} 范式。部分失败也整体成功返回
 * （code=0，与上传/批量审核的容忍语义一致）。</p>
 */
public class BatchDeleteResponse {

    private List<BatchDeleteItem> results;
    private BatchDeleteSummary summary;

    public BatchDeleteResponse() {}

    public BatchDeleteResponse(List<BatchDeleteItem> results, BatchDeleteSummary summary) {
        this.results = results;
        this.summary = summary;
    }

    public List<BatchDeleteItem> getResults() { return results; }
    public BatchDeleteSummary getSummary() { return summary; }
}
