package com.hisilicon.knowledge.platform.knowledge.command;

import java.time.LocalDateTime;

/**
 * 模型回调解析后的命令对象
 * <p>
 * 由 {@code DmsProcessCallbackConsumer} 解析入站信封得到，按 {@link #documentId}
 * 是否为空路由到文档级或知识库级处理。{@link #messageId} 为模型回调信封的 messageId，
 * 用作幂等去重主键。
 * </p>
 *
 * @param messageId   模型回调信封 messageId（去重主键）
 * @param kbId        知识库 ID
 * @param documentId  文档 ID（KB 级回调为 null）
 * @param eventType   PROCESSING_COMPLETED / PROCESSING_FAILED
 * @param error       失败描述（FAILED 时可能有）
 * @param errorCode   失败错误码（FAILED 时可能有，如 404/500）
 * @param chunkCount  切片数量（COMPLETED 时回传，KB 级为 0）
 * @param processedAt 处理完成/失败时间（回调时回传）
 * @param totalDuration 总耗时（原样透传字符串，不做解析转换，可能为 null）
 * @param callbackedAt 回调发起时间（原样透传字符串，不做解析转换，可能为 null）
 */
public record ProcessCallbackCommand(
        String messageId,
        String kbId,
        String documentId,
        String eventType,
        String error,
        String errorCode,
        Integer chunkCount,
        LocalDateTime processedAt,
        String totalDuration,
        String callbackedAt) {

    /** 是否为处理成功回调 */
    public boolean completed() {
        return "PROCESSING_COMPLETED".equals(eventType);
    }

    /** 是否为知识库级回调（无 documentId） */
    public boolean kbLevel() {
        return documentId == null || documentId.isBlank();
    }
}
