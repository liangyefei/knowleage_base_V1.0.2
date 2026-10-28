package com.hisilicon.knowledge.platform.knowledge.query;

import java.util.List;

/**
 * 全局文档搜索查询条件（封装 {@link DocumentGlobalSearchQueryService#globalSearch} 入参，
 * 避免 7 参方法签名）
 *
 * @param docName     关键词模糊（null/空白 = 不限，大小写不敏感；按 searchField 匹配标题/内容）
 * @param docTypes    文档类型分类多选（DOCUMENT/CODE/CONFIG/OTHER，null=不限）
 * @param chipModels  芯片型号多选（null/空 = 不限；任一命中即返回——OR）。
 *                    匹配口径：对应<b>知识库名称</b>（KB 以芯片型号命名，大小写不敏感），
 *                    不查 document_metadata.chip_model
 * @param searchField 关键词匹配范围（接口层已归一化）：title=仅标题、content=仅文件抽取内容
 *                    （document_contents，仅 READY 且 filePath 与 documents.file_path 等值——
 *                    即"审核通过的最新版"——可命中）、all=标题或内容（OR）
 * @param sortField   排序字段（null/空白 = time）；time=更新时间倒序，title=标题升序（不区分大小写）。
 *                    relevance 相关度排序已下线，兼容保留入参，按 time 处理
 * @param page        页码（1-based）
 * @param pageSize    每页条数
 */
public record GlobalSearchQuery(String docName, List<String> docTypes, List<String> chipModels,
                                String searchField, String sortField, int page, int pageSize) {
}
