package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;
import java.util.List;

/**
 * tus 上传会话仓储接口（upload_sessions 表）
 * <p>会话状态机与 offset 推进由 {@link UploadSession} 聚合根封装；仓储仅持久化。</p>
 */
public interface UploadSessionRepository {

    /** 按 ID 查询会话 */
    Uni<UploadSession> findById(UploadSessionId id);

    /** 持久化新会话 */
    Uni<UploadSession> persist(UploadSession entity);

    /** 更新会话（merge 持久化，用于 offset/status 推进） */
    Uni<UploadSession> update(UploadSession entity);

    /**
     * 查询批次下未完成会话（断点恢复清单 / 中止清理用）。
     * 返回 status 为 CREATED 或 UPLOADING 的会话。
     */
    Uni<List<UploadSession>> findIncompleteByBatch(String batchId);

    /**
     * 查询已超期且未完成的会话（清理 job 用）。
     * 返回 status 为 CREATED 或 UPLOADING 且 expires_at < now 的会话。
     */
    Uni<List<UploadSession>> findExpiredIncomplete(LocalDateTime now);

    /**
     * zip 异步解压进度 +1（bulk update，1 轮次，不 fetch 整实体）。
     * PROCESSING 期间逐条目调用；失败（并发中止等）不影响调用方。
     */
    Uni<Integer> bumpEntriesDone(String sessionId);

    /**
     * 查询卡在 PROCESSING 且超期未推进的会话（崩溃兜底清理 job 用）。
     * 返回 status=PROCESSING 且 updated_at 早于阈值的会话。
     */
    Uni<List<UploadSession>> findStaleProcessing(LocalDateTime threshold);

    /**
     * 查询知识库下全部进行中会话（CREATED/UPLOADING/PROCESSING）。
     * <p>用途：知识库/类别删除时作废在传会话——删除提交后落库校验必然失败，
     * 与其让会话在解压完成后逐条目报错收尾，不如显式作废（ABORTED + 临时文件移回收站）。</p>
     */
    Uni<List<UploadSession>> findActiveByKbId(String kbId);

    /**
     * 查询目标文档上指定用户的活跃会话（CREATED/UPLOADING）——doc 用途（DOC_EDIT /
     * VERSION_OVERRIDE）断点发现用：前端刷新/换设备后据此找回 sessionId 续传。
     *
     * @param targetDocumentId 目标文档 ID
     * @param operatorId       当前用户（仅本人会话可见）
     * @return 活跃会话（至多一条业务约束，无则空列表）
     */
    Uni<List<UploadSession>> findActiveByTargetDocument(String targetDocumentId, String operatorId);
}
