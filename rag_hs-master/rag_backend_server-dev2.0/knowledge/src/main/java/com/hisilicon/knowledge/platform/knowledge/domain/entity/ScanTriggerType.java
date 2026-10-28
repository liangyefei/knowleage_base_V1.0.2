package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 检测任务触发类型。
 */
public enum ScanTriggerType {
    /** 用户立即检测（勾选 KB / 文件夹） */
    MANUAL,
    /** 文档切片就绪后自动触发 */
    AUTO
}
