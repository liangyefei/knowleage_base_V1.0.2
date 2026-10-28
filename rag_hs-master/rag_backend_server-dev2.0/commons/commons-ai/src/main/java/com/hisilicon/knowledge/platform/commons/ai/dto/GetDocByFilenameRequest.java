package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型 {@code get_doc_by_filename} 请求体。
 *
 * @param collectionName 知识库 id（模型 {@code collection_name}）
 * @param filename       模型挂载全路径（{@code /kb-files/} + 文档相对 filePath）
 */
public record GetDocByFilenameRequest(
        @JsonProperty("collection_name") String collectionName,
        String filename) {
}