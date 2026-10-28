package com.hisilicon.knowledge.platform.llm.command;

/**
 * 会话中上传文件命令参数（G.MET.01：>5 参数方法收敛为参数对象）
 *
 * @param conversationId 会话 ID
 * @param account        当前账号（归属校验）
 * @param fileName       文件原始名
 * @param content        文件内容
 * @param mimeType       文件 MIME 类型
 * @param locale         语言环境（错误消息国际化）
 */
public record UploadConversationFileCommand(String conversationId, String account,
                                            String fileName, byte[] content, String mimeType,
                                            String locale) {
}
