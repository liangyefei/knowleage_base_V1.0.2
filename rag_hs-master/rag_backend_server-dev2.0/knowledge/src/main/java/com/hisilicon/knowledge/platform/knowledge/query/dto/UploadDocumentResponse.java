package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 文档上传响应（含逐文件结果 + 汇总统计）
 */
public class UploadDocumentResponse {

    private List<FileUploadResult> results;
    private UploadSummary summary;

    public UploadDocumentResponse() {}

    public UploadDocumentResponse(List<FileUploadResult> results, UploadSummary summary) {
        this.results = results;
        this.summary = summary;
    }

    public List<FileUploadResult> getResults() { return results; }
    public UploadSummary getSummary() { return summary; }
}
