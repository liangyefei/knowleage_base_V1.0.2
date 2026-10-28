package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 统一处理 Badcase 工单命令（修改状态 + 填写处理备注）。
 *
 * @param ticketId    工单 ID
 * @param targetStatus 目标状态码（PENDING / PROCESSING / RESOLVED / REJECTED）
 * @param remark       处理备注（1~1000 字）
 */
public record HandleBadcaseCommand(
        @NotBlank String ticketId,
        @NotBlank String targetStatus,
        @NotBlank @Size(max = 1000) String remark
) {
}
