package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 重命名节点命令（统一树节点操作，仅改名称）
 * <p>只更新节点名称，不触碰其他字段（KB 的 description、类别的 sortOrder/inheritParent
 * 保持原值 —— 与 updateKb/editCategory 的全量替换语义不同，树面板改名场景专用）。</p>
 *
 * @param nodeId   节点 ID（知识库 ID 或类别 ID，必填）
 * @param nodeType 节点类型：KB / CATEGORY（必填）
 * @param name     新名称（必填；KB 3-50 字符，CATEGORY 1-50 字符，统一按 1-50 校验）
 */
public record RenameNodeCommand(
        @NotBlank(message = "{app.node.id.notblank}")
        @SizeWithName(fieldName = "nodeId", max = 64)
        String nodeId,

        @NotBlank(message = "{app.node.type.notblank}")
        String nodeType,

        @NotBlank(message = "{app.node.name.notblank}")
        @SizeWithName(fieldName = "name", min = 1, max = 50)
        String name) {
}
