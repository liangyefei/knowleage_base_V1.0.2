package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新热门词命令（全量替换：内容 / 所属知识库 / 排序号 / 状态）
 *
 * @param id        热门词 ID（必填）
 * @param content   热门词内容（必填，≤200 字符，同知识库内唯一）
 * @param kbId      所属知识库 ID（必填）
 * @param sortOrder 排序号（可选，默认 0）
 * @param status    状态：1=启用 / 0=停用（必填）
 */
public record UpdateHotWordCommand(
        @NotBlank(message = "{app.hotword.id.notblank}")
        String id,

        @NotBlank(message = "{app.hotword.content.notblank}")
        @Size(max = 200, message = "{app.hotword.content.size}")
        String content,

        @NotBlank(message = "{app.hotword.kbId.notblank}")
        String kbId,

        Integer sortOrder,

        @NotNull(message = "{app.hotword.status.notnull}")
        Short status) {

    public UpdateHotWordCommand {
        sortOrder = (sortOrder == null) ? 0 : sortOrder;
    }
}
