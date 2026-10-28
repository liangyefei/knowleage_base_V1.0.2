package com.hisilicon.knowledge.platform.knowledge.dict.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 切换字典条目启用/禁用状态命令
 * <p>在 1（启用）与 2（禁用）之间切换。软删除（0）走 delete 通道，本命令不接受 0。
 * 命令层仅校验 presence；1/2 取值校验由服务层依据实体常量完成。</p>
 *
 * @param id     字典条目 ID
 * @param status 目标状态：1=启用，2=禁用
 */
public record ChangeDictItemStatusCommand(
        @NotBlank(message = "{app.dict.id.notblank}")
        String id,

        @NotNull(message = "{app.dict.status.notnull}")
        Short status
) {
}
