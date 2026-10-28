package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导入批次进度（GET /kb/import/batches/{id} 返回体）
 * <p>进度由 upload_sessions 聚合：未完成会话清单（uploading/created）供断点恢复前端逐文件 HEAD 续传。</p>
 *
 * @param batchId     批次 ID
 * @param batchName    批次名称
 * @param kbId         知识库 ID
 * @param status       批次状态（UPLOADING/COMPLETED/PARTIAL/ABORTED）
 * @param totalFiles   文件总数
 * @param totalSize    总字节数
 * @param completed    已完成（落库 SUCCESS）数
 * @param skipped      已跳过（同名 SKIP）数
 * @param failed       失败数
 * @param aborted      已中止数
 * @param expired      已过期数
 * @param uploading    上传中会话 ID（断点续传清单）
 * @param created      已创建未上传会话 ID（断点续传清单）
 * @param createdAt    批次创建时间
 */
public class ImportBatchProgress {
    private String batchId;
    private String batchName;
    private String kbId;
    private String status;
    private long totalFiles;
    private long totalSize;
    private long completed;
    private long skipped;
    private long failed;
    private long aborted;
    private long expired;
    /** zip 解压跳过条目数（不支持类型等，COMPLETED 终态聚合；单文件同名跳过不计入，走 skipped） */
    private long ignored;
    /**
     * 结果摘要（"文件名: 明细"，来自 session.error 列；zip 分桶统计如
     * 「入库成功 1701/1856；跳过：不支持类型 148、重名冲突 4、空文件 3」；
     * COMPLETED 也可能非空——error 列已扩展为通用结果摘要）。封顶 {@code RESULT_SUMMARY_CAP} 条。
     */
    private List<String> resultSummaries;
    private List<String> uploading;
    private List<String> created;
    private LocalDateTime createdAt;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getBatchName() { return batchName; }
    public void setBatchName(String batchName) { this.batchName = batchName; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getTotalFiles() { return totalFiles; }
    public void setTotalFiles(long totalFiles) { this.totalFiles = totalFiles; }
    public long getTotalSize() { return totalSize; }
    public void setTotalSize(long totalSize) { this.totalSize = totalSize; }
    public long getCompleted() { return completed; }
    public void setCompleted(long completed) { this.completed = completed; }
    public long getSkipped() { return skipped; }
    public void setSkipped(long skipped) { this.skipped = skipped; }
    public long getFailed() { return failed; }
    public void setFailed(long failed) { this.failed = failed; }
    public long getAborted() { return aborted; }
    public void setAborted(long aborted) { this.aborted = aborted; }
    public long getExpired() { return expired; }
    public void setExpired(long expired) { this.expired = expired; }
    public long getIgnored() { return ignored; }
    public void setIgnored(long ignored) { this.ignored = ignored; }
    public List<String> getUploading() { return uploading; }
    public void setUploading(List<String> uploading) { this.uploading = uploading; }
    public List<String> getResultSummaries() { return resultSummaries; }
    public void setResultSummaries(List<String> resultSummaries) { this.resultSummaries = resultSummaries; }
    public List<String> getCreated() { return created; }
    public void setCreated(List<String> created) { this.created = created; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
