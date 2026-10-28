package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 文档名称下拉选项（按文件名模糊选择文档，供前端下拉）
 *
 * @param id    文档 ID
 * @param title 文档标题（原始文件名去扩展名，对应 documents.title 列）
 */
public record DocumentNameOption(String id, String title) {
}
