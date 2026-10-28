package com.hisilicon.knowledge.platform.knowledge.dict.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 新增字典条目命令
 *
 * @param typeCode  所属字典类型编码（必须存在且未删除）
 * @param label     显示名称（1-200 字符）
 * @param value     存储值（写入 metadata 的实际值，1-200 字符；同类型下唯一）
 * @param sortOrder 排序号（可选；null = 自动按"同级最大 + 1"，首个从 1 开始）
 */
public record CreateDictItemCommand(
        @NotBlank(message = "{app.dict.typeCode.notblank}")
        String typeCode,

        @NotBlank(message = "{app.dict.label.notblank}")
        @SizeWithName(fieldName = "label", min = 1, max = 200)
        String label,

        @NotBlank(message = "{app.dict.value.notblank}")
        @SizeWithName(fieldName = "value", min = 1, max = 200)
        String value,

        @Min(value = 0, message = "{app.dict.sortOrder.min}")
        Integer sortOrder
) {
}
