package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 冲突检测任务（批次）PO。
 */
@Entity
@Table(name = "conflict_scan_jobs")
public class ConflictScanJobPO {

    @Id
    public String id;

    @Column(name = "batch_no", nullable = false, length = 20)
    public String batchNo;

    @Column(name = "kb_id", length = 36)
    public String kbId;

    @Column(name = "trigger_type", nullable = false, length = 10)
    public String triggerType;

    @Column(name = "mode", nullable = false, length = 12)
    public String mode;

    @Column(name = "scope_nodes", length = 1000)
    public String scopeNodesJson;

    @Column(name = "scope_fingerprint", length = 128)
    public String scopeFingerprint;

    @Column(name = "status", nullable = false, length = 12)
    public String status;

    @Column(name = "total_documents", nullable = false)
    public Integer totalDocuments = 0;

    @Column(name = "scanned_chunks", nullable = false)
    public Integer scannedChunks = 0;

    @Column(name = "new_conflict_count", nullable = false)
    public Integer newConflictCount = 0;

    @Column(name = "hit_conflict_count", nullable = false)
    public Integer hitConflictCount = 0;

    @Column(name = "failed_items", nullable = false)
    public Integer failedItems = 0;

    @Column(name = "error", length = 500)
    public String error;

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "finished_at")
    public LocalDateTime finishedAt;

    @Column(name = "created_by", nullable = false, length = 50)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    /** 模型端 task_id（提交模型后回填）。 */
    @Column(name = "model_task_id", length = 64)
    public String modelTaskId;

    /** 本次提交给模型端的文件路径列表 JSON（50 个文件路径可能超过 2000 字符，用 TEXT）。 */
    @Column(name = "file_paths_json", columnDefinition = "TEXT")
    public String filePathsJson;

    /** chunk 进度（done）。 */
    @Column(name = "progress_done")
    public Integer progressDone;

    /** chunk 进度（total）。 */
    @Column(name = "progress_total")
    public Integer progressTotal;

    /** 模型端返回的冲突总数。 */
    @Column(name = "conflict_count")
    public Integer conflictCount;
}
