package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.Map;

/**
 * 文档元数据视图（编辑回填用）
 * <p>{@code GET /kb/documents/metadata?documentId=...} 的返回体。聚合编辑表单回填所需的
 * 全部字段：documents 表的可编辑列（title/author/remark）+ 当前版本号 +
 * document_metadata 表的全部 key-value。标签不再平铺，统一由 {@code metadata.tag} 承载，与上传一致。</p>
 *
 * <h3>字段对齐</h3>
 * <ul>
 *   <li>{@code title}/{@code author}/{@code remark}：对应 {@link com.hisilicon.knowledge.platform.knowledge.command.EditDocumentCommand}
 *       同名字段，前端可直接回填。</li>
 *   <li>{@code currentVersion}：只读展示当前版本号；编辑接口本身不改版本（版本覆盖走单独端点）。</li>
 *   <li>{@code metadata}：含上传 UploadDocumentCommand 全部项——document_metadata 的 meta_key →
 *       meta_value（product/chip_model/sensitivity/official/access_policy 等）+ 从 documents 列组装的
 *       title/author/tag(逗号串)/remark/version(=currentVersion)，key 名对齐上传 JSON 名。
 *       label 不在此返回，前端复用 {@code GET /kb/metadata/dict-options} 的字段显示名。</li>
 * </ul>
 *
 * @param documentId     文档 ID
 * @param title          文档标题
 * @param author         文档作者（自由文本，可空）
 * @param remark         文档备注（自由文本，可空）
 * @param currentVersion 当前版本号（只读展示，可空）
 * @param metadata       文档元数据（含上传 UploadDocumentCommand 全部项：document_metadata 的 meta_key → meta_value
 *                       + 从 documents 列组装的 title/author/tag(逗号串)/remark/version；无元数据时为空 Map）
 */
public record DocumentMetadataView(String documentId, String title, String author,
                                   String remark,
                                   String currentVersion, Map<String, String> metadata) {
}
