package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 文档仓储接口
 */
public interface DocumentRepository {

    /**
     * 按 ID 查询文档
     */
    Uni<DocumentEntity> findById(DocumentId id);

    /**
     * 持久化文档
     */
    Uni<DocumentEntity> persist(DocumentEntity entity);

    /**
     * 更新文档（merge 持久化）
     */
    Uni<DocumentEntity> update(DocumentEntity entity);

    /**
     * 按 kbId + categoryId + title 查找文档（同名检测用）
     * <p>排除已废弃（DEPRECATED）的文档——废弃后名称可复用。</p>
     *
     * @param kbId       知识库 ID
     * @param categoryId 类别 ID（可为 null）
     * @param title      文档标题
     * @return 匹配的文档列表（通常 0~1 条）
     */
    Uni<List<DocumentEntity>> findByKbIdAndTitle(String kbId, String categoryId, String title);

    /**
     * 批量审核-知识库范围：查询指定知识库下所有审核中（REVIEWING）的文档
     * <p>含根目录文档（categoryId is null）；DB 层直接过滤 status='REVIEWING'，
     * 非审核中文档不计入（不属于批量审核目标，避免成百上千已发布文档作为噪音进入结果）。</p>
     *
     * @param kbId 知识库 ID
     * @return 该知识库下所有 REVIEWING 文档
     */
    Uni<List<DocumentEntity>> findReviewingByKbId(String kbId);

    /**
     * 批量审核-分类范围：查询指定分类集合下所有审核中（REVIEWING）的文档
     * <p>分类集合应已包含选中分类及其全部子分类（由 {@code CategoryTreeService.collectSubtreeIds} 收集）。
     * 空集合直接返回空列表（避免 {@code IN ()} 语法错误）。不包含根目录文档（categoryId 为 null），
     * 因为根目录不属于任何分类。</p>
     *
     * @param kbId        知识库 ID（用于二次限定，防止跨知识库误命中）
     * @param categoryIds 分类 ID 集合（空集合 → 空列表）
     * @return 命中分类下所有 REVIEWING 文档
     */
    Uni<List<DocumentEntity>> findReviewingByCategoryIds(String kbId, java.util.Collection<String> categoryIds);

    /**
     * 批量审核-文档范围：按文档 ID 批量加载文档（全状态）
     * <p>用于 DOCUMENT 模式：加载全部指定文档（含非 REVIEWING），由应用层逐条判定
     * （REVIEWING 处理、非 REVIEWING 跳过、未命中报告 SKIPPED）。空集合直接返回空列表。</p>
     *
     * @param documentIds 文档 ID 集合（空集合 → 空列表）
     * @return 命中的文档列表（全状态）
     */
    Uni<List<DocumentEntity>> findByIds(java.util.Collection<String> documentIds);

    /**
     * 批量提交审核-知识库范围：查询指定知识库下所有草稿（DRAFT）的文档
     * <p>含根目录文档（categoryId is null）；DB 层直接过滤 status='DRAFT'，
     * 非草稿文档不计入（不属于批量提交审核目标，避免已发布/审核中文档作为噪音进入结果）。</p>
     *
     * @param kbId 知识库 ID
     * @return 该知识库下所有 DRAFT 文档
     */
    Uni<List<DocumentEntity>> findDraftByKbId(String kbId);

    /**
     * 批量提交审核-分类范围：查询指定分类集合下所有草稿（DRAFT）的文档
     * <p>分类集合应已包含选中分类及其全部子分类（由 {@code CategoryTreeService.collectSubtreeIds} 收集）。
     * 空集合直接返回空列表（避免 {@code IN ()} 语法错误）。不包含根目录文档（categoryId 为 null），
     * 因为根目录不属于任何分类。</p>
     *
     * @param kbId        知识库 ID（用于二次限定，防止跨知识库误命中）
     * @param categoryIds 分类 ID 集合（空集合 → 空列表）
     * @return 命中分类下所有 DRAFT 文档
     */
    Uni<List<DocumentEntity>> findDraftByCategoryIds(String kbId, java.util.Collection<String> categoryIds);

    /**
     * 删除前置校验-分类范围：统计指定分类集合下处于给定生命周期状态的文档数
     * <p>用于删除知识库/类别前的阻塞校验：统计 REVIEWING/PUBLISHED/ARCHIVED 等不可随目录
     * 级联删除的文档。分类集合应已包含被删分类及其全部后代（{@code collectSubtreeIds} 收集）。
     * 不含根目录文档（categoryId 为 null，根目录不属于任何分类）。空分类集合返回 0。</p>
     *
     * @param kbId        知识库 ID（二次限定，防跨库误命中）
     * @param categoryIds 分类 ID 集合（空集合 → 0）
     * @param statuses    要统计的生命周期状态集合
     * @return 命中的文档数
     */
    Uni<Long> countByCategoryIdsAndStatusIn(String kbId,
                                            java.util.Collection<String> categoryIds,
                                            java.util.Collection<String> statuses);

    /**
     * 删除前置校验-知识库范围：统计指定知识库下处于给定生命周期状态的文档数
     * <p>含根目录文档（categoryId is null）。用于删除知识库前的阻塞校验。</p>
     *
     * @param kbId     知识库 ID
     * @param statuses 要统计的生命周期状态集合
     * @return 命中的文档数
     */
    Uni<Long> countByKbIdAndStatusIn(String kbId, java.util.Collection<String> statuses);

    /**
     * 目录级联删除目标：查询指定分类集合下处于给定生命周期状态的文档（全字段）
     * <p>用于删除类别时收集可级联物理删除的文档（DRAFT/DEPRECATED）。
     * 不含根目录文档（categoryId 为 null）。空分类集合返回空列表。</p>
     *
     * @param kbId        知识库 ID（二次限定，防跨库误命中）
     * @param categoryIds 分类 ID 集合（空集合 → 空列表）
     * @param statuses    目标生命周期状态集合
     * @return 命中的文档列表
     */
    Uni<List<DocumentEntity>> findByCategoryIdsAndStatusIn(String kbId,
                                                           java.util.Collection<String> categoryIds,
                                                           java.util.Collection<String> statuses);

    /**
     * 知识库级联删除目标：查询指定知识库下处于给定生命周期状态的文档（全字段，含根目录）
     * <p>用于删除知识库时收集可级联物理删除的文档（DRAFT/DEPRECATED）。</p>
     *
     * @param kbId     知识库 ID
     * @param statuses 目标生命周期状态集合
     * @return 命中的文档列表
     */
    Uni<List<DocumentEntity>> findByKbIdAndStatusIn(String kbId, java.util.Collection<String> statuses);

    /**
     * 查询文档全部历史版本的文件路径（document_versions.file_path）
     * <p>物理删除文档前收集：与当前版本文件（documents.file_path）一并移入回收站，
     * 避免版本覆盖过的文档（has_published_version=TRUE）删除后历史版本文件泄漏在存储上。</p>
     *
     * @param documentId 文档 ID
     * @return 历史版本文件路径列表（无历史版本 → 空列表）
     */
    Uni<List<String>> findVersionFilePaths(String documentId);

    /**
     * 批量查询多个文档的历史版本文件路径（目录/知识库级联删除用）
     * <p>替代逐文档 {@link #findVersionFilePaths(String)}（N 次查询 → 1 次），事务内语句数与文档数解耦。</p>
     *
     * @param documentIds 文档 ID 集合（空集合 → 空列表）
     * @return 非空历史版本文件路径列表（去重由调用方处理）
     */
    Uni<List<String>> findVersionFilePathsByDocumentIds(java.util.Collection<String> documentIds);

    /**
     * 批量物理删除文档（级联：document_metadata + document_versions + documents），状态守卫。
     * <p>目录/知识库级联删除用：3 条批量语句替代逐文档 3N 条。每条语句带
     * {@code status in (目标状态)} 守卫——并发窗口内状态翻转为不可删（如 DRAFT→REVIEWING）
     * 的文档整体跳过（documents 与其子表同进退，不会出现"文档存活而子表被清"的半删状态）。</p>
     * <p>须在事务内调用。</p>
     *
     * @param documentIds 待删文档 ID 集合（空集合 → 直接完成）
     * @param statuses    允许删除的生命周期状态集合（守卫条件）
     * @return 完成信号
     */
    Uni<Void> deleteAllByIds(java.util.Collection<String> documentIds,
                             java.util.Collection<String> statuses);

    /**
     * 归档文档版本（写入 document_versions 表）
     *
     * @param version 归档版本信息
     * @return 完成信号
     */
    Uni<Void> archiveVersion(DocumentVersion version);

    /**
     * 物理删除文档（级联：document_metadata + document_versions + documents）
     * <p>须在事务内调用（业务事务与 outbox 消息同事务提交）。</p>
     *
     * @param id 文档 ID
     * @return 完成信号
     */
    Uni<Void> delete(DocumentId id);

    /**
     * 删除 documents 行 + document_metadata（<b>保留 document_versions 历史行</b>）。
     * <p>当前版本级删除路径专用：documents 行删除后 current_version 随之消失，
     * 历史版本行与文件保留。须在事务内调用。</p>
     *
     * @param id 文档 ID
     * @return 完成信号
     */
    /** default 实现：仅供测试桩免实现（等价级联删除），真实仓储必须覆盖 */
    default Uni<Void> deleteDocumentKeepVersions(DocumentId id) {
        return delete(id);
    }

    /**
     * 按文档 ID + 版本号查历史版本（document_versions 表）
     *
     * @param documentId    文档 ID
     * @param versionNumber 版本号
     * @return 版本记录；不存在返回 null
     */
    Uni<DocumentVersion> findVersion(String documentId, String versionNumber);

    /**
     * 标记文档进入模型处理中（processStatus: PENDING/FAILED → PROCESSING）。
     * <p>由 outbox relay 在 DOCUMENT_PUBLISHED 消息发送到 Kafka 成功后调用，
     * 与 outbox markSent 同事务。{@code where process_status IN ('PENDING','FAILED')} 守卫：
     * 消息发送成功即开启新处理周期——上一周期遗留的 FAILED 属陈旧数据，随新周期一并清掉
     * （否则新周期的 COMPLETED 回调会被终态守卫当过期跳过，文档冻结在"已发布+向量化失败"）；
     * COMPLETED 不在守卫内（回调先于 relay markProcessing 到达置终态的竞态保护，见
     * {@code DocumentCommandService#processCallback} 第 3 步守卫），已是 PROCESSING 幂等跳过；
     * 同时清空旧失败原因（process_error/process_error_code）——进入新一轮处理，旧失败不再相关。</p>
     *
     * @param id 文档 ID
     * @return 完成信号
     */
    Uni<Void> markProcessing(DocumentId id);

    /**
     * 按文件存储相对路径（{@code file_path}）批量解析文档 id（供对话 docs 出处补 id 字段）。
     *
     * <p>匹配模型 SSE docs 事件中 {@code file} 去除 {@code /kb-files} 前缀后的值。
     * 两段解析：先查 {@code documents.file_path}（当前路径，全状态）；未命中路径回退
     * {@code document_versions.file_path}（版本覆盖后旧路径仅存在于归档表），兜底段仅解析到
     * <b>未归档、未删除</b>（排除 ARCHIVED/DEPRECATED）的文档。documents 优先（同路径两表都命中
     * 时以当前路径为准）；未命中的路径不出现在返回映射中。</p>
     *
     * @param filePaths 文件存储相对路径集合（空/null → 空映射）
     * @return {@code filePath → documentId} 映射（未命中路径不在其中）
     */
    Uni<java.util.Map<String, String>> findIdsByFilePaths(java.util.Collection<String> filePaths);

    /**
     * 按文件存储相对路径批量解析文档引用（id + 版本号 + 版本状态），供对话 docs 出处补 id/version/versionStatus 字段。
     *
     * <p>解析规则与 {@link #findIdsByFilePaths} 完全一致（documents 优先、document_versions 兜底、
     * 兜底段仅未归档未删除文档），额外携带命中版本号与版本行状态：documents 当前路径命中=
     * current_version/文档生命周期状态；document_versions 历史路径命中=version_number/版本行 status
     * （同路径多版本行取最新归档）。</p>
     *
     * @param filePaths 文件存储相对路径集合（空/null → 空映射）
     * @return {@code filePath → PathRef(documentId, version, versionStatus)} 映射（未命中路径不在其中）
     */
    /**
     * default 实现：委托 {@link #findIdsByFilePaths}（version/versionStatus 置 null）。
     * 仅供测试桩/装饰器免实现；真实仓储（{@code DocumentRepositoryImpl}）覆盖为携带版本号的两段解析。
     */
    default Uni<java.util.Map<String, PathRef>> findRefsByFilePaths(java.util.Collection<String> filePaths) {
        return findIdsByFilePaths(filePaths)
                .map(ids -> {
                    java.util.Map<String, PathRef> result = new java.util.HashMap<>();
                    ids.forEach((path, id) -> result.put(path, new PathRef(id, null, null)));
                    return result;
                });
    }

    /**
     * 版本级归档/废弃：仅更新版本行状态（PUBLISHED → ARCHIVED/DEPRECATED），文档生命周期不变。
     *
     * @param documentId    文档 ID
     * @param versionNumber 版本号
     * @param status        目标状态（{@link DocumentVersion#STATUS_ARCHIVED} / {@link DocumentVersion#STATUS_DEPRECATED}）
     * @return 是否实际更新（false=版本不存在或已下线，幂等）
     */
    /** default 实现：仅供测试桩免实现，真实仓储必须覆盖 */
    default Uni<Boolean> updateVersionStatus(String documentId, String versionNumber, String status) {
        throw new UnsupportedOperationException("updateVersionStatus not supported by this implementation");
    }

    /**
     * 版本级物理删除：移除 document_versions 中指定版本行（文件回收与向量清理由调用方完成）。
     *
     * @param documentId    文档 ID
     * @param versionNumber 版本号
     * @return 是否实际删除（false=版本不存在，幂等）
     */
    /** default 实现：仅供测试桩免实现，真实仓储必须覆盖 */
    default Uni<Boolean> deleteVersion(String documentId, String versionNumber) {
        throw new UnsupportedOperationException("deleteVersion not supported by this implementation");
    }

    /** 路径解析引用项：{@param documentId} 文档 id，{@param version} 命中版本号，{@param versionStatus} 版本行状态 */
    record PathRef(String documentId, String version, String versionStatus) {
    }
}
