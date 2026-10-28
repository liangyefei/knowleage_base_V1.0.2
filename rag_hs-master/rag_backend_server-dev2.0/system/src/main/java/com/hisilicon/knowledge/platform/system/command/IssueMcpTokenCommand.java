package com.hisilicon.knowledge.platform.system.command;

import jakarta.validation.constraints.Size;

/**
 * 签发 MCP 令牌命令。
 *
 * @param label 备注（例如 "Trae 笔记本"，便于用户区分多台设备）
 * @author hisilicon
 */
public record IssueMcpTokenCommand(@Size(max = 128) String label) {
}