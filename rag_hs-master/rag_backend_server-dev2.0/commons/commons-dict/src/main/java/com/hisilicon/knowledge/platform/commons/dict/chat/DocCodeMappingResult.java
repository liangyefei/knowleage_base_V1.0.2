package com.hisilicon.knowledge.platform.commons.dict.chat;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 代码↔文档映射结果（dual-mcp {@code /doc_code} 响应透传，直接对象无信封）。
 *
 * <p>{@code results} 按 {@code direction} 呈现两种结构，用 {@link JsonNode} 透传：
 * doc2code 为平铺列表 {@code [{file, ...}]}；code2doc 为按文档分组 {@code [{doc, symbols: [...]}]}。</p>
 *
 * @param file      回显：实际参与匹配的 file（模型项目内相对路径）
 * @param project   回显：项目名（模型规范化为 {@code projects-<kbId>}）
 * @param direction 实际方向：doc2code / code2doc / null（未命中）
 * @param matched   判向结果：{@code document}（命中文档）/ {@code code_file}（命中代码文件）/ null（未命中）
 * @param count     results 实际条数（doc2code=映射条数；code2doc=文档组数；0 + hint 亦属成功）
 * @param results   映射结果（结构见上，JsonNode 透传）
 * @param hint      服务端提示信息（如未命中原因），可为 null
 * @param elapsedMs 服务端处理耗时（毫秒）
 */
public record DocCodeMappingResult(
        String file,
        String project,
        String direction,
        String matched,
        Integer count,
        JsonNode results,
        String hint,
        Long elapsedMs) {
}
