package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 文档图谱关系片段查询结果（dual-mcp {@code POST /graph_fragment} 响应，
 * 《API-GRAPH-FRAGMENT.md》§2.4，跨模块共享 DTO）。
 *
 * <p>给定文档 KG 一条关系边（subject→predicate→object）+ palace（kbId），返回该关系来源文档中的
 * 对应片段。响应为直接对象（无信封）；{@code count=0} 为数据真值（关系来源文档未入 mempalace 等），
 * 非错误，编排层透传不转 A0430。mempalace 不可达时响应带 {@code error} 字段（正常响应无此字段）。</p>
 *
 * <p>{@link Fragment#docViewUrl()} 为平台侧追加字段（模型响应无此字段）：由客户端实现按
 * {@code room + palace} 重写为平台 {@code /kg/doc-view?doc={room}&kb={palace}} 查询串
 * （对齐 doc_code/files 的 url 重写先例），供前端「查看原文 ↗」跳转；基址缺省时为 null，
 * 前端隐藏链接。{@code sourceFile} 为模型侧容器内全路径，仅透传展示用途。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphFragmentResult(
        String subject,
        String predicate,
        String object,
        Integer count,
        List<Fragment> fragments,
        @JsonProperty("elapsed_ms") Double elapsedMs,
        String error) {

    /**
     * 片段（关系来源文档中同时包含主体与客体——退化为含其一——的 chunk 文本）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fragment(
            String text,
            @JsonProperty("source_file") String sourceFile,
            String room,
            @JsonProperty("chunk_index") String chunkIndex,
            @JsonProperty("doc_view_url") String docViewUrl) {
    }
}
