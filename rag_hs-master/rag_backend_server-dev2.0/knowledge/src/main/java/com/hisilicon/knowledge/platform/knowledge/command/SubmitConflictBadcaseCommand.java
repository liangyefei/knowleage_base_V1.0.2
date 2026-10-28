package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交 Badcase 工单命令。
 * <p>recordId 由 URL 路径传入，不从 body 反序列化（Resource 层重建 Command）。</p>
 *
 * @param recordId    冲突记录 ID（由 Resource 层从 @PathParam 注入）
 * @param description 问题描述（必填，1~1000 字）
 */
public record SubmitConflictBadcaseCommand(
        String recordId,

        @NotBlank(message = "问题描述不能为空")
        @Size(max = 1000, message = "问题描述长度不能超过 1000")
        String description) {
}
