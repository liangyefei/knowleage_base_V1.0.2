package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 导航树节点 DTO（左侧面板懒加载）
 * <p>懒加载：{@code GET /kb/navTree} 无参返回全部活跃知识库 + 每个 KB 的根子类别（深度 = 1）；
 * 带 {@code parentId} 返回该类别下的直接子类别（再下一层）。类别节点的 {@code children}
 * 恒为空列表 —— 子级始终由前端带 {@code parentId=当前节点id} 再次调用本接口加载。</p>
 *
 * @param nodeId        节点 ID（知识库 ID 或类别 ID）
 * @param nodeType      节点类型：KB = 知识库，CATEGORY = 类别
 * @param name          节点名称
 * @param kbId          所属知识库 ID（KB 节点 = 自身 id；CATEGORY 节点 = 所属 KB），
 *                      前端调用 {@code GET /kb/{kbId}/documents} 右侧文档列表必需
 * @param documentCount 该节点及全部后代下的文档总数（递归计数，KB 节点 = 整个知识库）
 * @param categoryCount 该节点及全部后代下的活跃类别总数（递归计数，KB 节点 = 知识库全部类别数）
 * @param hasChildren   是否还有子级（KB 节点 = 是否有根子类别；CATEGORY 节点 = 是否有子类别）
 * @param children      子节点列表（仅 KB 节点携带根子类别；CATEGORY 节点恒为空，懒加载）
 */
public record NavTreeItem(String nodeId, String nodeType, String name, String kbId,
                          long documentCount, long categoryCount,
                          boolean hasChildren, List<NavTreeItem> children) {

    public static final String TYPE_KB = "KB";
    public static final String TYPE_CATEGORY = "CATEGORY";
}
