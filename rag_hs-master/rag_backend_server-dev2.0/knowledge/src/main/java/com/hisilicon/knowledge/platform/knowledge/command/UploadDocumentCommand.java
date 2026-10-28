package com.hisilicon.knowledge.platform.knowledge.command;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 上传文档元数据（metadata JSON 反序列化目标）
 * <p>kbId 和 categoryId 已提升为独立的 {@code @RestForm} 参数，
 * 本 record 只承载纯业务元数据。所有字段均可选——不传或 null 表示不设置对应元数据。</p>
 *
 * <h3>JSON 属性名契约</h3>
 * <p>前端通过 {@code GET /kb/metadata/dict-options} 获取下拉选项，返回的 {@code metaKey}
 * 即 {@code kb_dict_types.controlled_meta_keys} 中的键名（DB 层 meta_key，如
 * {@code chip_model} / {@code sensitivity}）。前端以这些 metaKey 作为 metadata JSON 的字段名提交，
 * 故本 record 的 JSON 属性名必须与 controlled_meta_keys 对齐（{@link JsonProperty}），
 * 同时以 camelCase Java 字段名作为 {@link JsonAlias} 兼容旧调用方。
 * 仅 {@code tags} 字段例外：其 metaKey 为建议列表 typeCode {@code tag}（不在元数据白名单，
 * 写入 {@code documents.tags} 列）。</p>
 *
 * @param product       产品名称 → document_metadata key=product（可选）
 * @param chipModel     芯片型号 → document_metadata key=chip_model（可选）
 * @param version       版本标签 → document_metadata key=version（可选）
 * @param securityLevel 安全等级 → document_metadata key=sensitivity（可选）
 * @param official      官方标识 → document_metadata key=official（可选，值 OFFICIAL/NON_OFFICIAL）
 * @param title         文档标题 → documents.title（可选；上传/版本覆盖单文件时非空用之前端值，
 *                      null/空白=文件名去扩展名推导；ZIP 批量忽略用各自文件名）
 * @param author        文档作者 → documents.author（可选，自由文本）
 * @param tags          文档标签列表 → documents.tags 逗号分隔（可选；JSON 名为 {@code tag}，
 *                      兼容单值字符串经 {@code ACCEPT_SINGLE_VALUE_AS_ARRAY} 归一为单元素列表）
 * @param remark        文档备注 → documents.remark（可选，自由文本，≤500 字符）
 * @param accessPolicy  访问策略（预留，本期不在 UI 展示，JSON 序列化存入 document_metadata key=access_policy）
 */
@RegisterForReflection
public record UploadDocumentCommand(
        String product,

        @JsonProperty("chip_model") @JsonAlias("chipModel")
        String chipModel,

        String version,

        @JsonProperty("sensitivity") @JsonAlias("securityLevel")
        String securityLevel,

        String official,

        String title,

        String author,

        @JsonProperty("tag") @JsonAlias("tags")
        List<String> tags,

        String remark,

        @JsonProperty("access_policy") @JsonAlias("accessPolicy")
        Object accessPolicy
) {

    private static final Logger logger = LoggerFactory.getLogger(UploadDocumentCommand.class);

    /**
     * 构建 document_metadata dict 映射（product/chip_model/sensitivity/official/access_policy；空=不写）。
     * version 不入 document_metadata（由 documents.current_version 承载）。
     * access_policy 为 Object（可能是字符串/对象），序列化为 JSON 字符串存入。
     */
    public java.util.Map<String, String> toDocumentMetadata() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (product != null && !product.isBlank()) {
            map.put("product", product.trim());
        }
        if (chipModel != null && !chipModel.isBlank()) {
            map.put("chip_model", chipModel.trim());
        }
        if (securityLevel != null && !securityLevel.isBlank()) {
            map.put("sensitivity", securityLevel.trim());
        }
        if (official != null && !official.isBlank()) {
            map.put("official", official.trim());
        }
        if (accessPolicy != null) {
            try {
                map.put("access_policy", new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(accessPolicy));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                logger.warn("access_policy serialization failed, ignored: {}", e.getMessage());
            }
        }
        return map;
    }

    /**
     * 转批量/单文件统一元数据形态（持久化 jsonb 形态）：
     * documentMetadata=toDocumentMetadata()；version/author/tags/remark 直传；title 直传
     * （批量导入不消费该值，标题恒取文件名去扩展名；版本覆盖 tus 会话消费——null=文件名推导）。
     * 供批次 default_metadata 与 tus 逐文件 metadata 复用（前端发同一份 JSON）。
     */
    public ImportFileMetadata toImportMetadata() {
        return new ImportFileMetadata(toDocumentMetadata(), version, author, tags, remark, title);
    }
}
