package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocChunk;

import java.util.List;

/**
 * 模型 {@code get_doc_by_filename} 响应信封（{@code code/msg/data}）。
 *
 * <p>模型成功返回 {@code code=200} + {@code data} 为切片数组；非 200 视为失败。未知字段忽略
 * （{@link JsonIgnoreProperties}，防模型字段漂移）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocByFilenameResponse(
        int code,
        String msg,
        List<DocChunk> data) {

    public boolean isSuccess() {
        return code == 200;
    }
}