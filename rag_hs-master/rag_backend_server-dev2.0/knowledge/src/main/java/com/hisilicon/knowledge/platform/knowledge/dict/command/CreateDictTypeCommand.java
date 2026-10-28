package com.hisilicon.knowledge.platform.knowledge.dict.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建字典类型命令
 *
 * @param typeCode           类型编码（唯一，小写下划线，1-50 字符）
 * @param typeName           类型显示名（1-200 字符）
 * @param controlledMetaKeys 管控的 metadata 键名，逗号分隔（可选；null/空 = 仅展示用）
 */
public record CreateDictTypeCommand(
        @NotBlank(message = "{app.dict.typeCode.notblank}")
        String typeCode,

        @NotBlank(message = "{app.dict.typeName.notblank}")
        @SizeWithName(fieldName = "typeName", min = 1, max = 200)
        String typeName,

        @SizeWithName(fieldName = "controlledMetaKeys", max = 500)
        String controlledMetaKeys
) {
}
