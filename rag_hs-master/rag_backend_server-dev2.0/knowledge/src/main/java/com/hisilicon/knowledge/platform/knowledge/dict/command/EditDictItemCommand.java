package com.hisilicon.knowledge.platform.knowledge.dict.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 编辑字典条目命令
 * <p>label/value 全量替换；<b>sortOrder 为 null 时保持原值</b>
 * （用户确认的例外：排序字段不参与全量替换语义）。</p>
 * <p>修改 value 不影响已引用该值的历史元数据——元数据写入后是历史事实，
 * 字典变更仅影响未来的可选选项（管理界面需提示用户）。</p>
 *
 * @param id        字典条目 ID
 * @param label     新的显示名称（1-200 字符）
 * @param value     新的存储值（1-200 字符；同类型下唯一）
 * @param sortOrder 新的排序号（可选；null = 保持原值）
 */
public record EditDictItemCommand(
        @NotBlank(message = "{app.dict.id.notblank}")
        String id,

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
