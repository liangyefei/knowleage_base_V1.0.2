package com.hisilicon.knowledge.platform.knowledge.domain.service;

import io.smallrye.mutiny.Uni;

/**
 * 文档切片就绪后的自动冲突检测钩子（端口）。
 *
 * <p>由 {@code DocumentCommandService.processCallback} 在 COMPLETED 事务提交后旁路调用。
 * 默认实现吞掉所有异常，绝不影响文档发布主链路。</p>
 */
public interface ConflictAutoScanHook {

    /**
     * 切片发布成功后的旁路钩子。
     *
     * @param kbId       知识库 ID
     * @param documentId 刚完成切片的文档 ID
     * @return 完成信号（实现内部已兜底异常，不向上抛）
     */
    Uni<Void> afterChunkPublished(String kbId, String documentId);
}
