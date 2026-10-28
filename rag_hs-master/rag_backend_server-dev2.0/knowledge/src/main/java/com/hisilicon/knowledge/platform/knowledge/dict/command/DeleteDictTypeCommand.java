package com.hisilicon.knowledge.platform.knowledge.dict.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 删除字典类型命令（软删，级联软删该类型下全部条目）
 * <p>删除前校验该类型下任一未删除条目值是否被业务数据引用（元数据表 meta_value /
 * version 专用列）：有关联引用则拒绝删除（A0420）；无引用方级联软删。</p>
 *
 * @param id 字典类型 ID
 */
public record DeleteDictTypeCommand(
        @NotBlank(message = "{app.dict.id.notblank}")
        String id
) {
}
