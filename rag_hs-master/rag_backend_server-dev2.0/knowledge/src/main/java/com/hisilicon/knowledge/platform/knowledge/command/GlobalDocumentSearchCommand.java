package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;

import java.util.List;

/**
 * 文档全局搜索命令（跨知识库统一查询接口，POST body）
 * <p>与 {@link SearchDocumentsCommand} 并存：后者按 {@code kbId} 限定单知识库范围，
 * 本命令不绑定知识库，面向"按分类/名称/芯片型号检索相关文档"的跨库检索场景。
 * 默认仅检索 {@code status=PUBLISHED} 的文档（"相关文档"语义），不暴露生命周期状态筛选。</p>
 *
 * @param docName    文档名称模糊（可选，大小写不敏感；searchField=all/content 时同时匹配文件抽取内容）
 * @param docTypes   文档类型分类多选（DOCUMENT/CODE/CONFIG/OTHER，可选）
 * @param chipModels 芯片型号多选（可选；对应知识库名称，KB 以芯片型号命名，大小写不敏感；
 *                   命中任一即返回——OR）
 * @param searchField 搜索范围（可选，null/空白=默认 all）：title=仅标题、content=仅文件内容、
 *                    all=标题或内容（OR）。非法值由接口层校验拒绝（A0420）
 * @param sortField  排序字段（可选，默认 time）：time=更新时间倒序、title=标题升序（不区分大小写）、
 *                   relevance 相关度排序已下线（原依赖模型接口），仅为兼容保留入参，按 time 处理
 * @param page       页码（可选，默认 1）
 * @param pageSize   每页条数（可选，默认 20，最大 1000）
 */
public record GlobalDocumentSearchCommand(
        @SizeWithName(fieldName = "docSearchKey", min = 1, max = 100)
        String docName,

        List<String> docTypes,

        List<String> chipModels,

        String searchField,

        String sortField,

        Integer page,

        Integer pageSize) {
}
