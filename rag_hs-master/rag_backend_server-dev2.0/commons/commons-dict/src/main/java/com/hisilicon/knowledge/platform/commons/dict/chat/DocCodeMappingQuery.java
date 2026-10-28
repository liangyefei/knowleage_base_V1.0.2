package com.hisilicon.knowledge.platform.commons.dict.chat;

/**
 * 代码↔文档映射查询参数（对应 dual-mcp {@code /doc_code} 请求，wire 字段 snake_case，
 * 由 commons-ai 实现映射到 {@code DocCodeRequest}）。
 *
 * @param file       必填：目标版本文件的存储相对路径（版本差异体现在该路径上）
 * @param project    项目名（= kbId）
 * @param direction  映射方向：doc2code（文档→代码）/ code2doc（代码→文档）
 * @param limit      返回映射条数上限（null = 客户端缺省补 20）
 * @param withSource 命中项附带源码片段（null = 不带）
 * @param withDoc    命中项附带文档原文（null = 不带）
 * @param exact      精确文件名匹配（false/缺省 = 路径包含匹配）
 */
public record DocCodeMappingQuery(
        String file,
        String project,
        String direction,
        Integer limit,
        Boolean withSource,
        Boolean withDoc,
        Boolean exact) {
}
