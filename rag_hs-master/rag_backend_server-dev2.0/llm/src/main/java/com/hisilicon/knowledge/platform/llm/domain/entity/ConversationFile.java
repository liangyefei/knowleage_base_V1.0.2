package com.hisilicon.knowledge.platform.llm.domain.entity;

/**
 * 会话问答文件元数据（每会话最多 1 个）。
 *
 * <p>G.MET.01：把 attachFile 的文件描述收敛为领域值对象；模型侧临时知识库 id
 * （{@code tempKbId}）是上传模型后的产物，单独作为 attachFile 的参数。</p>
 *
 * @param fileName 原始文件名
 * @param fileSize 文件大小（bytes）
 * @param filePath 存储相对路径（kb/chat/{conversationId}/{文件名}）
 * @param mimeType MIME 类型
 * @param fileType 文件类型分类：DOCUMENT/CODE/CONFIG/OTHER
 */
public record ConversationFile(String fileName, Long fileSize, String filePath,
                               String mimeType, String fileType) {
}
