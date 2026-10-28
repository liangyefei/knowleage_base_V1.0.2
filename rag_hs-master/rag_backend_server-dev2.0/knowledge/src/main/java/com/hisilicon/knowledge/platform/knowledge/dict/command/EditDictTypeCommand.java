package com.hisilicon.knowledge.platform.knowledge.dict.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 编辑字典类型命令（全量替换：typeName/controlledMetaKeys 按传入值替换）
 * <p>typeCode 创建后不可修改（标识符不可变，预置与自定义类型统一规则）。
 * controlledMetaKeys 为 null/空 = 清空管控。</p>
 *
 * @param id                 字典类型 ID
 * @param typeName           新的类型显示名（1-200 字符）
 * @param controlledMetaKeys 新的受控 meta_key 列表（逗号分隔，null/空 = 清空）
 */
public record EditDictTypeCommand(
        @NotBlank(message = "{app.dict.id.notblank}")
        String id,

        @NotBlank(message = "{app.dict.typeName.notblank}")
        @SizeWithName(fieldName = "typeName", min = 1, max = 200)
        String typeName,

        @SizeWithName(fieldName = "controlledMetaKeys", max = 500)
        String controlledMetaKeys
) {
}
