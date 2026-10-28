package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import io.smallrye.mutiny.Uni;

/**
 * 审核历史仓储接口
 * <p>
 * 在文档生命周期状态变更（提交审核/撤回/通过/驳回/归档/废弃）时，
 * 同事务内 {@link #save} 落库一条审核历史记录，保证审计追溯完整性。
 * </p>
 */
public interface ReviewHistoryRepository {

    /**
     * 保存一条审核历史记录
     *
     * @param entry 审核历史内容（文档/操作人/动作/状态变更/版本/意见，见 {@link ReviewHistoryEntry}）
     * @return 完成信号
     */
    Uni<Void> save(ReviewHistoryEntry entry);
}
