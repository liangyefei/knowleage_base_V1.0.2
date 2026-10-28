package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 删除节点命令（统一树节点操作）
 * <p>知识库节点 → 级联软删该 KB 全部类别并发布 KB_DELETED 事件；
 * 类别节点 → 级联软删全部后代类别。已删除/不存在视为同一错误（A0430）。</p>
 *
 * @param nodeId   节点 ID（知识库 ID 或类别 ID，必填）
 * @param nodeType 节点类型：KB / CATEGORY（必填）
 */
public record DeleteNodeCommand(
        @NotBlank(message = "{app.node.id.notblank}")
        @SizeWithName(fieldName = "nodeId", max = 64)
        String nodeId,

        @NotBlank(message = "{app.node.type.notblank}")
        String nodeType) {
}
