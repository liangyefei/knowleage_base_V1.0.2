package com.hisilicon.knowledge.platform.commons.web;

import java.util.List;

/**
 * 分页查询时返回的数据
 * @param pageNumber 当前页
 * @param pageSize 每页数据量
 * @param totalPage 总页数
 * @param totalRow 总数据条数
 * @param records 数据列表
 */
public record PageResult<T> (long pageNumber, long pageSize, long totalPage, long totalRow, List<T> records) {
}
