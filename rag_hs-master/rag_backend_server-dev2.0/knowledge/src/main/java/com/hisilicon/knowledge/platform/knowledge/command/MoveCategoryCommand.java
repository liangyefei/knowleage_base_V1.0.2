package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 移动类别命令
 * <p>仅支持在知识库内部移动（同一知识库内变更父类别），不支持跨知识库移动。
 * 子类别与文档随树结构自动跟随。</p>
 *
 * @param categoryId     被移动的类别 ID（必填）
 * @param targetParentId 目标父类别 ID（可选，为空表示移到知识库根目录）
 */
public record MoveCategoryCommand(
        @NotBlank(message = "{app.category.id.notblank}")
        String categoryId,

        @SizeWithName(fieldName = "targetParentId", max = 64)
        String targetParentId) {
}
