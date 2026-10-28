package com.hisilicon.knowledge.platform.knowledge.command;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * 批量物理删除文档命令（<b>仅版本级</b>）
 * <p>防误操作护栏：批量删除只支持按版本逐条删除（每个条目必须携带 version），
 * 不支持文档级批量删除（文档级范围过大、易误删）。每个条目移除 document_versions
 * 中指定版本行 + 回收文件 + 通知模型清该版本向量，文档本体不变；单条失败不影响其余。</p>
 * <p>与 {@link BatchSubmitReviewCommand} 的批量范式一致：items 经规整后要求 1~100 条，
 * 任一条目缺 version 或为空/超限 → 应用层抛 {@code BusinessException(A0420)}
 * （批量端点业务错误以 code 返回，非 400）。</p>
 *
 * @param items 版本级删除目标列表（每条 {documentId, version}，version 必填）
 */
@RegisterForReflection
public record BatchDeleteCommand(
        List<DeleteItem> items) {
    /**
     * 批量删除条目：{@param documentId} 文档 ID，{@param version} 版本号（必填，非空=版本级删除）
     */
    public record DeleteItem(String documentId, String version) {
    }
}
