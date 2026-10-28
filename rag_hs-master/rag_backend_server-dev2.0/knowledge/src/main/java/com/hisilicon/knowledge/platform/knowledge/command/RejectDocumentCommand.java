package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.i18n.validation.SizeWithName;
import jakarta.validation.constraints.NotBlank;

/**
 * 驳回文档命令
 *
 * @param documentId 文档 ID（必填）
 * @param reason     驳回原因（必填）
 */
public record RejectDocumentCommand(
        @NotBlank(message = "{app.document.id.notblank}")
        String documentId,
        @NotBlank(message = "{app.document.review.reason.notblank}")
        @SizeWithName(fieldName = "reason", min = 1, max = 200)
        String reason) {
}
