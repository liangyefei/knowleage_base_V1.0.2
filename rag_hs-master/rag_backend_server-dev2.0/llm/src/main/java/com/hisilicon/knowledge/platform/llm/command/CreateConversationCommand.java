package com.hisilicon.knowledge.platform.llm.command;

/**
 * 创建会话命令参数（G.MET.01：>5 参数方法收敛为参数对象）
 *
 * @param account  用户账号（JWT sub）
 * @param title    标题（可空）
 * @param fileName 文件原始名（可空 = 无文件会话）
 * @param content  文件内容（fileName 非空时必填）
 * @param mimeType 文件 MIME 类型
 * @param locale   语言环境（错误消息国际化）
 */
public record CreateConversationCommand(String account, String title,
                                        String fileName, byte[] content, String mimeType,
                                        String locale) {
}
