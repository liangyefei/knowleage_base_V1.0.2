package com.hisilicon.knowledge.platform.knowledge.command;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 批量提交审核命令
 * <p>支持三种范围（scope）之一对处于草稿（DRAFT）状态的文档批量提交审核（DRAFT → REVIEWING）：
 * <ul>
 *   <li>{@code KB} —— 整个知识库下所有 DRAFT 文档（含根目录 categoryId=null）</li>
 *   <li>{@code CATEGORY} —— 知识库下多个分类（递归包含子分类）下的 DRAFT 文档</li>
 *   <li>{@code DOCUMENT} —— 显式列出的多个文档（非 DRAFT / 不存在计为 SKIPPED）</li>
 * </ul>
 * 与 {@link BatchReviewCommand} 对齐，但无需 {@code action}（单一动作）和 {@code reason}（提交审核无驳回原因）。
 * 跨字段条件校验（scope 决定哪组字段必填）在应用层完成，
 * 失败抛 {@code BusinessException(A0420)}，与现有 {@code invalidNodeTypeValue} 范式一致。
 *
 * @param scope       批量提交审核范围：{@code KB} / {@code CATEGORY} / {@code DOCUMENT}（必填）
 * @param kbId        知识库 ID（三种模式均必填——批量提交审核是单知识库操作，权限按 KB 粒度；
 *                    DOCUMENT 模式下不属于该 KB 的文档记为 SKIPPED）
 * @param categoryIds 分类 ID 列表（{@code CATEGORY} 模式必填，支持多分类，递归子分类）
 * @param documentIds 文档 ID 列表（{@code DOCUMENT} 模式必填，支持多文档）
 */
@RegisterForReflection
public record BatchSubmitReviewCommand(
        @NotBlank(message = "{app.batch.submit-review.scope.notblank}")
        String scope,

        String kbId,

        List<String> categoryIds,

        List<String> documentIds) {
}
