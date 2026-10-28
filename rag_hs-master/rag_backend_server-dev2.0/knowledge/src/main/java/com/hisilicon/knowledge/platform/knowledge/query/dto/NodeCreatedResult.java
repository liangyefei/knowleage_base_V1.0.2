package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 新建节点结果（前端插入树结构用）
 *
 * @param nodeId   新节点 ID
 * @param nodeType 节点类型（统一树节点操作创建结果恒为 CATEGORY）
 * @param name     节点名称
 */
public record NodeCreatedResult(String nodeId, String nodeType, String name) {
}
