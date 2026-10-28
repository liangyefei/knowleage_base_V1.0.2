package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 检测任务状态机：
 * <pre>
 * QUEUED --提交模型成功--> RUNNING --全部证据落库--> COMPLETED
 *                                  ├--部分失败--> PARTIAL
 *                                  ├--提交/判定失败--> FAILED
 *                                  └--用户中止--> ABORTED
 * </pre>
 */
public enum ScanJobStatus {
    QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == ABORTED;
    }
}
