package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * dual-mcp {@code /doc_code} 请求体（代码↔文档映射）。字段名即 wire 格式（snake_case，与 KgQueryRequest
 * 风格一致，不加 {@code @JsonProperty}）。
 *
 * <p>{@code @JsonInclude(NON_NULL)}：模型（FastAPI/Pydantic）对 {@code with_source/with_doc}
 * 传显式 {@code null} 直接 422（"Input should be a valid integer"），未传字段一律省略；
 * 标志位统一用 int 语义（0=不附，1=附带），缺省由 compact constructor 补齐
 * （with_source/with_doc/exact 缺省 0，limit 缺省 20），确保序列化产物永远不含 null 标志位。</p>
 *
 * @param file        必填：图谱项目内相对路径（非内部存储路径，调用前剥离 kb/{dirName}/ 前缀）
 * @param project     项目名（= kbId，§5「kbId==dual-mcp 项目名」）
 * @param direction   映射方向：{@code doc2code}（文档→代码）/{@code code2doc}（代码→文档）
 * @param limit       返回映射条数上限，缺省 20
 * @param with_source 命中项附带源码片段：0=不附（缺省），1=附带
 * @param with_doc    命中项附带文档原文：0=不附（缺省），1=附带
 * @param exact       精确文件名匹配：0=路径包含匹配（缺省），1=精确
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocCodeRequest(
        String file,
        String project,
        String direction,
        Integer limit,
        Integer with_source,
        Integer with_doc,
        Integer exact) {

    /** 缺省补齐（与服务端默认对齐，显式补齐避免序列化出 null）：limit=20，标志位=0 */
    public DocCodeRequest {
        if (limit == null) {
            limit = 20;
        }
        if (with_source == null) {
            with_source = 0;
        }
        if (with_doc == null) {
            with_doc = 0;
        }
        if (exact == null) {
            exact = 0;
        }
    }
}