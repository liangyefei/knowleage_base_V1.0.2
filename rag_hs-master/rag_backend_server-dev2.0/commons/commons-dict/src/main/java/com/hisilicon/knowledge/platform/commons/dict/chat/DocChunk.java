package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 文档切片（模型服务 {@code get_doc_by_filename} 返回的切片项，跨模块共享 DTO）。
 *
 * <p>字段对齐模型 chunk 结构：snake_case JSON 经 {@link JsonProperty} 映射为 camelCase Java 字段；
 * 未知字段忽略（{@link JsonIgnoreProperties}，防模型字段漂移）。llm 反序列化模型响应后由 knowledge
 * 端点透传返回（「提取 data」）。</p>
 *
 * <p>放于 commons-dict（llm 与 knowledge 均已依赖的共享层）：llm 不依赖 knowledge、knowledge 不依赖 llm
 * （架构文档 §3.14「llm 不依赖 knowledge」），二者经共享 DTO + 端口解耦。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocChunk(
        @JsonProperty("file_name") String fileName,
        String text,
        DocChunkMetadata metadata) {

    /**
     * 切片元数据（模型 chunk metadata 结构）。
     *
     * <p>均为可选字段：模型可能省略部分字段（缺失 → null）。{@code page_numbers}/{@code titles}
     * 为列表；未知字段忽略。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocChunkMetadata(
            String source,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("file_type") String fileType,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("chunk_index") Integer chunkIndex,
            @JsonProperty("total_chunks") Integer totalChunks,
            @JsonProperty("page_numbers") List<Integer> pageNumbers,
            List<String> titles,
            @JsonProperty("chunk_type") String chunkType) {
    }
}
