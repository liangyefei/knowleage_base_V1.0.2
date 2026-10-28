package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 变更热门词状态命令（启用/停用）
 *
 * @param id     热门词 ID（必填）
 * @param status 目标状态：1=启用 / 0=停用（必填，合法性由接口层校验）
 */
public record ChangeHotWordStatusCommand(
        @NotBlank(message = "{app.hotword.id.notblank}")
        String id,

        @NotNull(message = "{app.hotword.status.notnull}")
        Short status) {
}
