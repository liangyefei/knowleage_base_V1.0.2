package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 热门词 ID 命令（按 ID 定位单条热门词）
 *
 * @param id 热门词 ID（必填）
 */
public record HotWordIdCommand(
        @NotBlank(message = "{app.hotword.id.notblank}")
        String id) {
}
