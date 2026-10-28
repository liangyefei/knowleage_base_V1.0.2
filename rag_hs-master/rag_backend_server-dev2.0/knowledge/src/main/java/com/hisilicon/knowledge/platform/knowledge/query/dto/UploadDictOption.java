package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 上传页字典下拉选项项
 *
 * @param label 显示名称（来自字典条目 label）
 * @param value 存储值（写入 document_metadata 的实际值，来自字典条目 value）
 */
public record UploadDictOption(String label, String value) {
}
