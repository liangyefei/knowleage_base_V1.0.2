package com.hisilicon.knowledge.platform.knowledge.query;

import java.util.List;
import java.util.Map;

/**
 * 文档列表查询条件（封装 {@link DocumentQueryService#listDocuments} 入参，避免 10 参方法签名）
 *
 * @param kbId               知识库 ID
 * @param categoryId         类别 ID（null/空 = 根节点；非空 = 指定类别）
 * @param includeDescendants 是否递归包含当前节点全部后代下的文档（false = 仅当前目录直属；true = 递归含全部后代）
 * @param docName            文档名称模糊（null/空白 = 不限）
 * @param docTypes           文档类型分类多选（DOCUMENT/CODE/CONFIG/OTHER，null=不限）
 * @param statuses           生命周期状态多选（DRAFT/REVIEWING/PUBLISHED/ARCHIVED/DEPRECATED，null=不限）
 * @param processStatuses    模型处理状态多选（PENDING/PROCESSING/COMPLETED/FAILED，null=不限）
 * @param metadata           元数据筛选（meta_key → 匹配值，多 key 为 AND；值逗号分隔多值时按片段匹配；null/空 = 不限）。
 *                           特判：meta_key={@code version} 不查 document_metadata（生产无此行），
 *                           改按版本级行过滤——当前版行按 documents.current_version、历史版行按
 *                           document_versions.version_number 各自命中（版本级结果行语义）
 * @param page               页码（1-based）
 * @param pageSize           每页条数
 */
public record ListDocumentsQuery(String kbId, String categoryId, boolean includeDescendants,
                                 String docName, List<String> docTypes, List<String> statuses,
                                 List<String> processStatuses, Map<String, String> metadata,
                                 int page, int pageSize) {
}
