package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.time.LocalDateTime;

/**
 * 类别列表项 DTO（扁平浏览用，不返回树形结构）
 *
 * @param id          类别 ID
 * @param name        类别名称
 * @param hasChildren 是否含有子类别（前端据此显示展开/折叠图标）
 * @param sortOrder   排序号
 * @param createdAt   创建时间
 */
public record CategoryListItem(String id, String name, boolean hasChildren,
                               int sortOrder, LocalDateTime createdAt) {
}
