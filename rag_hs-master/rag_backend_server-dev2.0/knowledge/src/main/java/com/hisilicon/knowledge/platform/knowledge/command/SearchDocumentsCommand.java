package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * 文档搜索命令（统一查询接口，POST body）
 *
 * @param kbId               知识库 ID（必填）
 * @param categoryId         类别 ID（可选；{@code includeDescendants=false} 时 null = 知识库根目录文档，即 category_id IS NULL）
 * @param includeDescendants 是否包含当前节点全部后代下的文档（可选，默认 false，即仅查当前目录直属）。
 *                           true 且 categoryId 为空：查整个知识库（含根目录与所有子目录）；
 *                           true 且 categoryId 非空：查该类别及其全部后代类别下的文档
 * @param docName            文档名称模糊（可选）
 * @param docTypes           文档类型分类多选（DOCUMENT/CODE/CONFIG/OTHER，可选）
 * @param statuses           生命周期状态多选（DRAFT/REVIEWING/PUBLISHED/ARCHIVED/DEPRECATED，可选）
 * @param processStatuses     模型处理状态多选（PENDING/PROCESSING/COMPLETED/FAILED，可选）
 * @param officials          官方标识多选（字典 {@code official} 的值 OFFICIAL/NON_OFFICIAL，可选）。
 *                           值之间 OR（与 {@code metadata} 的多值逗号语义一致），与其它条件 AND。
 *                           等价于 {@code metadata["official"]}——两者同时传时以本字段为准（facade 归一，
 *                           见 {@code DocumentResource#mergeOfficials}）；都未传 = 不限（未标注 official
 *                           的文档返回 {@code official=null}，不被任一值命中）
 * @param metadata           元数据筛选（meta_key → 匹配值，多 key 为 AND；值逗号分隔多值时按片段匹配；可选）
 * @param page               页码（可选，默认 1）
 * @param pageSize           每页条数（可选，默认 20，最大 1000）
 */
public record SearchDocumentsCommand(
        @NotBlank(message = "{app.kb.id.notblank}")
        @SizeWithName(fieldName = "kbId", max = 64)
        String kbId,

        @SizeWithName(fieldName = "categoryId", max = 64)
        String categoryId,

        Boolean includeDescendants,

        @SizeWithName(fieldName = "docName", max = 200)
        String docName,

        List<String> docTypes,

        List<String> statuses,

        List<String> processStatuses,

        List<String> officials,

        Map<String, String> metadata,

        Integer page,

        Integer pageSize) {
}
