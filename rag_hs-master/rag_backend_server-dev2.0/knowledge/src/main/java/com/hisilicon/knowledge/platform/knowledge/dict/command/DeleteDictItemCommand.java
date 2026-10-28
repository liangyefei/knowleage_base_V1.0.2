package com.hisilicon.knowledge.platform.knowledge.dict.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 删除字典条目命令（软删）
 * <p>删除前校验该值是否被业务数据引用（元数据表 meta_value / version 专用列）：
 * 有关联引用则拒绝删除（A0420）；无引用方软删，软删后该值不再作为未来选项。</p>
 *
 * @param id 字典条目 ID
 */
public record DeleteDictItemCommand(
        @NotBlank(message = "{app.dict.id.notblank}")
        String id
) {
}
