package com.hisilicon.knowledge.platform.llm.query.dto;

/**
 * 手动触发分析响应。
 */
public record InsightAnalyzeResult(
        int scanned,
        int analyzed,
        int failed) {
}
