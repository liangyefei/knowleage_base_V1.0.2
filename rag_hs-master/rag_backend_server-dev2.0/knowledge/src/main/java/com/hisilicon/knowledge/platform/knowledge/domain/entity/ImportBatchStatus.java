package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 导入批次状态
 * <p>UPLOADING → COMPLETED（全部 COMPLETED/SKIPPED）/ PARTIAL（存在 FAILED）/ ABORTED（用户中止）。</p>
 */
public enum ImportBatchStatus {
    /** 上传中 */
    UPLOADING,
    /** 批次完成（所有会话到达 COMPLETED/SKIPPED 终态，无 FAILED/ABORTED） */
    COMPLETED,
    /** 部分失败（存在 FAILED 会话） */
    PARTIAL,
    /** 用户中止 */
    ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == ABORTED;
    }
}
