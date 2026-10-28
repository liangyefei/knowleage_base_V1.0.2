package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 已建立映射关系的文件清单（dual-mcp {@code POST /doc_code/files} 响应，跨模块共享 DTO）。
 *
 * <p>字段对齐《API-DOCCODE-FILES.md》§4 响应结构：snake_case JSON 经 {@link JsonProperty} 映射；
 * 未知字段忽略。响应为直接对象（无信封）。{@code count=0} 表示该库尚无映射，为数据真值而非错误。</p>
 *
 * <p><b>url 语义与模型侧不同</b>：适配层（commons-ai 实现）不透传模型返回的绝对地址（可能是集群内
 * svc 名或模型侧公网直连地址，浏览器未必可达），统一重写为平台自身的 {@code /kg/doc-view} 查询串
 * （{@code project={kbId}&file={file}}），复用既有 doc-view 门面的存储前缀裁剪与扩展名分流。</p>
 *
 * @param project   项目回显（{@code projects-<kbId>} 前缀名）
 * @param type      列表类型回显：code / doc
 * @param count     去重后的文件数（0=该库尚无映射，数据真值）
 * @param files     文件列表（按 file 路径升序）
 * @param elapsedMs 模型侧查询耗时（毫秒，可空）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocCodeFileListResult(
        String project,
        String type,
        Integer count,
        List<DocCodeFileItem> files,
        @JsonProperty("elapsed_ms") Double elapsedMs) {

    /**
     * 映射文件清单项。
     *
     * @param file 项目内相对路径（code 侧=符号所属文件；doc 侧=文档路径）
     * @param name 文件 basename（doc 侧=文档名）
     * @param refs 该文件的 DOCUMENTS_CODE 映射边数（code 侧同文件多符号时累加；可空——非数值容错）
     * @param url  「查看原文」跳转链接（平台侧重写为 /kg/doc-view 查询串，非模型绝对地址）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocCodeFileItem(
            String file,
            String name,
            Integer refs,
            String url) {
    }
}
