package com.hisilicon.knowledge.platform.knowledge.domain.entity;

/**
 * 文档文件属性（领域参数对象）。
 * <p>聚合"一次上传/覆盖/编辑更换文件"所携带的文件与展示属性，避免领域工厂与命令服务
 * 出现超长参数列表（G.MET.01）。{@code version} 仅上传/版本覆盖使用，编辑换文件时可为 null。</p>
 *
 * @param title    文档标题（新文件名去扩展名）
 * @param filePath 文件存储路径
 * @param fileSize 文件大小（bytes）
 * @param mimeType 文件 MIME 类型
 * @param docType  文档类型分类
 * @param author   作者（可空）
 * @param tags     标签逗号分隔串（可空）
 * @param remark   备注（可空）
 * @param version  版本号（上传/版本覆盖必填；编辑换文件 null=沿用当前版本）
 */
public record DocumentFileSpec(String title, String filePath, long fileSize, String mimeType,
                               String docType, String author, String tags, String remark,
                               String version) {
}
