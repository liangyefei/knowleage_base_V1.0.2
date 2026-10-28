package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 新建子文件夹命令（统一树节点操作）
 * <p>在指定父节点下创建子类别：父节点为知识库 → 创建根类别；
 * 父节点为类别 → 创建子类别。创建结果恒为 {@code CATEGORY} 节点。
 * 顶级知识库不在此接口创建（KB 有 description 等字段，由 {@code /kb/createKb} 负责）。</p>
 *
 * @param parentId       父节点 ID（知识库 ID 或类别 ID，必填）
 * @param parentNodeType 父节点类型：KB / CATEGORY（必填）
 * @param name           新节点名称（必填，1-50 字符）
 */
public record CreateNodeCommand(
        @NotBlank(message = "{app.node.id.notblank}")
        @SizeWithName(fieldName = "parentId", max = 64)
        String parentId,

        @NotBlank(message = "{app.node.parentNodeType.notblank}")
        String parentNodeType,

        @NotBlank(message = "{app.node.name.notblank}")
        @SizeWithName(fieldName = "name", min = 1, max = 50)
        String name) {
}
