package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 上传页字典驱动下拉选项分组 DTO
 * <p>数据源 = 系统字典（kb_dict_types + kb_dict_items，status=1），
 * 动态绑定：字典类型的 {@code controlledMetaKeys} 与上传表单支持的元数据 key 求交集。</p>
 *
 * @param metaKey        元数据键（document_metadata.meta_key，如 product / chip_model / version / sensitivity / official）；
 *                       建议列表组（tag）的 metaKey 为字典 typeCode（仅标识用，不写入 document_metadata）
 * @param label          前端显示名（国际化，取不到时回退为 metaKey 原值）
 * @param multiSelect    是否支持多选（true=前端渲染多选控件，提交时多个值逗号 join）
 * @param suggestionOnly 是否为纯建议列表（true=不校验值，仅展示供用户选择；false=字典管控，值必须 ∈ options）
 * @param options        可选值列表（按字典条目 sortOrder 升序、value 升序；同 key 多类型合并去重）
 */
public record UploadDictOptionGroup(String metaKey, String label, boolean multiSelect,
                                    boolean suggestionOnly,
                                    List<UploadDictOption> options) {
}
