package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量上传单文件解析后的元数据（jsonb 持久化形态）
 * <p>承载 complete 落库所需的全部元数据，create 时已解析并校验（fail-closed），
 * complete 时直接消费，不重解析、不重校验：</p>
 * <ul>
 *   <li>{@code documentMetadata}：dict 受控 meta_key→meta_value 映射，直接 {@code replaceAll} 到 document_metadata</li>
 *   <li>{@code version}：→ documents.current_version（导入/版本覆盖必填，编辑可选）</li>
 *   <li>{@code author}/{@code tags}/{@code remark}：→ documents 列</li>
 *   <li>{@code title}：→ documents.title。仅文档编辑（DOC_EDIT，null=不改，语义同 multipart edit）与
 *       版本覆盖（VERSION_OVERRIDE，null=文件名去扩展名推导）消费；批量导入不消费（标题恒取文件名去扩展名）。
 *       旧 jsonb 行无该键，反序列化为 null，向后兼容</li>
 * </ul>
 * <p>元数据仅落 document_metadata（见 §3.2 决策 7）；version 不入 document_metadata，由 current_version 承载。</p>
 *
 * @param documentMetadata dict 受控元数据映射（product/chip_model/sensitivity/official/access_policy）
 * @param version          版本标签（→ current_version）
 * @param author          作者（自由文本）
 * @param tags            标签列表（→ documents.tags 逗号 join）
 * @param remark          备注（自由文本）
 * @param title           文档标题（仅 DOC_EDIT/VERSION_OVERRIDE 消费；null=编辑不改/覆盖推导，见类注释）
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportFileMetadata(
        Map<String, String> documentMetadata,
        String version,
        String author,
        List<String> tags,
        String remark,
        String title) {

    /** 空元数据（无 dict 元数据、无列字段） */
    public static ImportFileMetadata empty() {
        return new ImportFileMetadata(new LinkedHashMap<>(), null, null, List.of(), null, null);
    }

    /**
     * 合并：批次默认 ⊕ 逐文件覆盖（逐文件优先）。返回新实例。
     * <p>documentMetadata：逐文件 key 覆盖批次默认同名 key；version/author/remark/title：
     * 逐文件非空则覆盖；tags：逐文件非空则覆盖。title 仅 doc 用途消费，导入合并链路透传不副作用。</p>
     */
    public static ImportFileMetadata merge(ImportFileMetadata defaults, ImportFileMetadata perFile) {
        if (defaults == null && perFile == null) {
            return empty();
        }
        if (defaults == null) {
            return perFile;
        }
        if (perFile == null) {
            return defaults;
        }
        Map<String, String> mergedMeta = new LinkedHashMap<>(defaults.documentMetadata() != null
                ? defaults.documentMetadata() : Map.of());
        if (perFile.documentMetadata() != null) {
            mergedMeta.putAll(perFile.documentMetadata());
        }
        return new ImportFileMetadata(
                mergedMeta,
                pick(perFile.version(), defaults.version()),
                pick(perFile.author(), defaults.author()),
                pick(perFile.tags(), defaults.tags()),
                pick(perFile.remark(), defaults.remark()),
                pick(perFile.title(), defaults.title()));
    }

    private static <T> T pick(T perFile, T def) {
        if (perFile != null) {
            return perFile;
        }
        return def;
    }
}
