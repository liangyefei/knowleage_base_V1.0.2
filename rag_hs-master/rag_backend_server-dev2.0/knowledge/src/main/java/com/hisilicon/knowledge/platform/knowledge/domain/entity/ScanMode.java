package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 检测任务扫描模式。
 */
public enum ScanMode {
    /** 全量：勾选子树下全部可检测文档 */
    FULL,
    /** 增量：changed 单文档，模型侧向量召回近邻比对 */
    INCREMENTAL
}
