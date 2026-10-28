package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 发起手动扫描（立即检测）命令。
 *
 * <p>新需求：只能单选一个知识库，该 KB 下可多选文件夹/文件。
 * documentIds（文档 ID 列表）与 categoryIds（分类 ID 列表，含后代）至少一项非空。</p>
 *
 * @param kbId         目标知识库 ID（必填、唯一）
 * @param documentIds  勾选的文档 ID 列表（documents.id，可空）
 * @param categoryIds  勾选的分类 ID 列表，展开含后代（可空）
 */
public record StartConflictScanCommand(
        @NotBlank(message = "知识库 ID 不能为空")
        String kbId,

        List<String> documentIds,

        List<String> categoryIds) {
}
