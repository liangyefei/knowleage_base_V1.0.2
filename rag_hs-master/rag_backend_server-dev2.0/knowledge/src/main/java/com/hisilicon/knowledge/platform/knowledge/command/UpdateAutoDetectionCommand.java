package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotNull;

/**
 * 切换自动检测开关命令。
 *
 * @param enabled 是否开启自动检测
 * @param kbId    目标知识库 ID（可空：空=全局开关；非空=该 KB 的覆盖开关）
 */
public record UpdateAutoDetectionCommand(
        @NotNull(message = "开关值不能为空")
        Boolean enabled,
        String kbId) {
}
