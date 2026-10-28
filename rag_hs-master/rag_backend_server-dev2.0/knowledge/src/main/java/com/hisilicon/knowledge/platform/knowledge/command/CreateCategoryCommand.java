package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建类别命令
 *
 * @param categoryName 类别名称（必填）
 * @param parentId     父类别 ID（可选，为空表示创建根类别）
 */
public record CreateCategoryCommand(
        @NotBlank(message = "{app.category.name.notblank}")
        @SizeWithName(fieldName = "categoryName", min = 1, max = 50)
        String categoryName,

        @SizeWithName(fieldName = "parentId", max = 64)
        String parentId) {
}
