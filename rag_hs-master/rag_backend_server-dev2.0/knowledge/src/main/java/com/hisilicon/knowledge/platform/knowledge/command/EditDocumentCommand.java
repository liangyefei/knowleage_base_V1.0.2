package com.hisilicon.knowledge.platform.knowledge.command;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * 文档编辑命令（POST /kb/documents/edit metadata JSON 反序列化目标）
 * <p>支持编辑文档标题、作者、标签、备注 + 全量替换字典管控元数据
 * （product/chip_model/version/sensitivity/official）。
 * 与上传表单字段保持一致（不含 kbId/categoryId/accessPolicy）。</p>
 *
 * <h3>JSON 属性名契约</h3>
 * <p>JSON 属性名与 {@link UploadDocumentCommand} 一致，对齐
 * {@code kb_dict_types.controlled_meta_keys}（DB 层 meta_key：{@code chip_model}/
 * {@code sensitivity}），并以 camelCase Java 字段名作为 {@link JsonAlias} 兼容旧调用方。
 * {@code tags} 字段的 JSON 名为建议列表 typeCode {@code tag}。</p>
 *
 * <h3>null 语义（全量替换，title 为特殊例外）</h3>
 * <ul>
 *   <li>{@code title}：{@code null} = 不改（特殊例外，标题不可清空）；
 *       非 {@code null} 且非 blank = 新标题</li>
 *   <li>{@code author}：{@code null} = 清空</li>
 *   <li>{@code tags}：{@code null} 或空列表 = 清空；非空 = 新标签列表（经校验：≤20 个，单个 ≤30 字符）</li>
 *   <li>{@code remark}：{@code null} = 清空</li>
 *   <li>{@code product}/{@code chipModel}/{@code version}/{@code securityLevel}/{@code official}：
 *       {@code null} = 清空该 metadata key；非 blank = 新值（经字典值校验）</li>
 * </ul>
 *
 * <p>当整个 metadata JSON 不传时，Facade 解析为 {@code null}，Service 不改任何字段。
 * 至少需传一个字段，否则 Service 层拒绝（A0420）。</p>
 *
 * <p>tus 复用：DOC_EDIT 用途的 tus 会话以本 record 为 metadata JSON 反序列化目标，
 * {@link #toImportMetadata()} 转为 {@link ImportFileMetadata} 落会话（create 时校验，complete 直接消费），
 * null 语义与 multipart 完全一致。</p>
 *
 * @param title        文档标题（{@code null}=不改，非 blank=新标题；空串→400）
 * @param author       文档作者（{@code null}=清空）
 * @param tags         文档标签列表（{@code null}/空=清空）
 * @param remark       文档备注（{@code null}=清空）
 * @param product      产品名称 → document_metadata key=product（{@code null}=清空该 key）
 * @param chipModel    芯片型号 → document_metadata key=chip_model
 * @param version      版本标签 → document_metadata key=version
 * @param securityLevel 安全等级 → document_metadata key=sensitivity
 * @param official     官方标识 → document_metadata key=official
 */
@RegisterForReflection
public record EditDocumentCommand(
        String title,
        String author,
        @JsonProperty("tag") @JsonAlias("tags")
        List<String> tags,
        String remark,
        String product,
        @JsonProperty("chip_model") @JsonAlias("chipModel")
        String chipModel,
        String version,
        @JsonProperty("sensitivity") @JsonAlias("securityLevel")
        String securityLevel,
        String official
) {

    /**
     * 转 tus DOC_EDIT 会话元数据形态（对齐 multipart edit 的字段映射）：
     * documentMetadata=product/chip_model/sensitivity/official（version 不入 map，由 current_version 承载，
     * 与 {@code DocumentCommandService.buildEditMetadata} 同规则）；title/author/tags/remark 直传
     * （null 语义由消费方 editWithFileFromTemp 按 multipart edit 解释）。
     */
    public ImportFileMetadata toImportMetadata() {
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
        return new ImportFileMetadata(map, version, author, tags, remark, title);
    }
}
