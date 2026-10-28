package com.hisilicon.knowledge.platform.knowledge.command;

/**
 * 移交 Badcase 工单命令（不改状态，只改处理人）。
 */
public record AssignBadcaseCommand(
        String ticketId,
        String handler,
        String handlerName,
        String remark) {
}
