package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * tus 上传会话状态机
 * <p>CREATED → UPLOADING → COMPLETED（正常）/ SKIPPED（同名跳过）/ FAILED（落库失败）/ ABORTED（终止）/ EXPIRED（超期）。
 * zip 会话 complete 时先转 PROCESSING（异步解压落库，PATCH 立即返 204），再 → COMPLETED/SKIPPED/FAILED。终态不可回退。</p>
 */
public enum UploadSessionStatus {
    /** 已创建，未上传 */
    CREATED,
    /** 上传中（已收至少一片 PATCH） */
    UPLOADING,
    /** zip 传完，后台异步解压落库中（单文件 complete 不经此态，直接同步落库） */
    PROCESSING,
    /** 传完并落库成功 */
    COMPLETED,
    /** 草稿态同名且 draftPolicy=SKIP，或命中 REVIEWING/PUBLISHED/ARCHIVED，跳过 */
    SKIPPED,
    /** 落库失败（DB 冲突、元数据非法等） */
    FAILED,
    /** 用户中止（DELETE / 批次 abort） */
    ABORTED,
    /** 会话超期（清理 job） */
    EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == SKIPPED || this == FAILED || this == ABORTED || this == EXPIRED;
    }
}
