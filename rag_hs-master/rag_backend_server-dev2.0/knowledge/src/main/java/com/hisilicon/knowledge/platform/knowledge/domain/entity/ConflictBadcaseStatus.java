package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 冲突 Badcase 提报状态。
 * <p>一冲突最多一张工单，提交后 NONE -> SUBMITTED。</p>
 * <p>注意：此枚举仅用于冲突检测模块的「是否提报 Badcase」标记，
 * 与新 Badcase 管理模块的 {@link BadcaseStatus}（工单处理四态）不是同一概念。</p>
 */
public enum ConflictBadcaseStatus {
    NONE, SUBMITTED
}
