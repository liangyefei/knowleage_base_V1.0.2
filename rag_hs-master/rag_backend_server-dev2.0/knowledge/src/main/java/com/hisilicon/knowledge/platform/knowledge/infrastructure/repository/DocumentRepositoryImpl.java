package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.DocumentMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 文档仓储实现
 */
@ApplicationScoped
public class DocumentRepositoryImpl implements DocumentRepository {

    private final DocumentPanacheRepository panacheRepo;
    private final DocumentVersionPanacheRepository versionPanacheRepo;
    private final DocumentMetadataPanacheRepository metadataPanacheRepo;
    private final DocumentMapper mapper = Mappers.getMapper(DocumentMapper.class);

    public DocumentRepositoryImpl(DocumentPanacheRepository panacheRepo,
                                  DocumentVersionPanacheRepository versionPanacheRepo,
                                  DocumentMetadataPanacheRepository metadataPanacheRepo) {
        this.panacheRepo = panacheRepo;
        this.versionPanacheRepo = versionPanacheRepo;
        this.metadataPanacheRepo = metadataPanacheRepo;
    }

    @Override
    public Uni<DocumentEntity> findById(DocumentId id) {
        return panacheRepo.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Uni<DocumentEntity> persist(DocumentEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<DocumentEntity> update(DocumentEntity entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<List<DocumentEntity>> findByKbIdAndTitle(String kbId, String categoryId, String title) {
        // 排除 DEPRECATED（废弃后名称可复用）；其他状态（DRAFT/REVIEWING/PUBLISHED/ARCHIVED）均在服务层处理冲突策略
        String excludeDeprecated = " and status <> 'DEPRECATED'";
        if (categoryId == null) {
            return panacheRepo.find("kbId = ?1 and categoryId is null and title = ?2" + excludeDeprecated, kbId, title)
                    .list().map(list -> list.stream().map(mapper::toDomain).toList());
        }
        return panacheRepo.find("kbId = ?1 and categoryId = ?2 and title = ?3" + excludeDeprecated, kbId, categoryId, title)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<DocumentEntity>> findReviewingByKbId(String kbId) {
        // 含根目录（categoryId is null）；DB 层过滤 status='REVIEWING'，非审核中文档不计入
        return panacheRepo.find("kbId = ?1 and status = ?2 order by createdAt", kbId, DocumentEntity.STATUS_REVIEWING)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<DocumentEntity>> findReviewingByCategoryIds(String kbId, java.util.Collection<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        // kbId 二次限定 + categoryId IN (...) + status='REVIEWING'
        return panacheRepo.find("kbId = ?1 and status = ?2 and categoryId in ?3 order by createdAt",
                        kbId, DocumentEntity.STATUS_REVIEWING, categoryIds)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<DocumentEntity>> findByIds(java.util.Collection<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        // 全状态加载，由应用层逐条判定
        return panacheRepo.find("id in ?1", documentIds)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<DocumentEntity>> findDraftByKbId(String kbId) {
        // 含根目录（categoryId is null）；DB 层过滤 status='DRAFT'，非草稿文档不计入
        return panacheRepo.find("kbId = ?1 and status = ?2 order by createdAt", kbId, DocumentEntity.STATUS_DRAFT)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<DocumentEntity>> findDraftByCategoryIds(String kbId, java.util.Collection<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        // kbId 二次限定 + categoryId IN (...) + status='DRAFT'
        return panacheRepo.find("kbId = ?1 and status = ?2 and categoryId in ?3 order by createdAt",
                        kbId, DocumentEntity.STATUS_DRAFT, categoryIds)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<Long> countByCategoryIdsAndStatusIn(String kbId,
                                                   java.util.Collection<String> categoryIds,
                                                   java.util.Collection<String> statuses) {
        if (categoryIds == null || categoryIds.isEmpty() || statuses == null || statuses.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        // kbId 二次限定 + categoryId IN (...) + status IN (...)；不含根目录（categoryId is null 不命中 IN）
        return panacheRepo.count("kbId = ?1 and status in ?2 and categoryId in ?3", kbId, statuses, categoryIds);
    }

    @Override
    public Uni<Long> countByKbIdAndStatusIn(String kbId, java.util.Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        // 含根目录文档（categoryId is null）
        return panacheRepo.count("kbId = ?1 and status in ?2", kbId, statuses);
    }

    @Override
    public Uni<List<DocumentEntity>> findByCategoryIdsAndStatusIn(String kbId,
                                                                  java.util.Collection<String> categoryIds,
                                                                  java.util.Collection<String> statuses) {
        if (categoryIds == null || categoryIds.isEmpty() || statuses == null || statuses.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        // kbId 二次限定 + categoryId IN (...) + status IN (...)；不含根目录
        return panacheRepo.find("kbId = ?1 and status in ?2 and categoryId in ?3", kbId, statuses, categoryIds)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<DocumentEntity>> findByKbIdAndStatusIn(String kbId, java.util.Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        // 含根目录文档（categoryId is null）
        return panacheRepo.find("kbId = ?1 and status in ?2", kbId, statuses)
                .list().map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<List<String>> findVersionFilePaths(String documentId) {
        // 仅取非空 file_path；物理删除前与当前版本文件一并移入回收站
        return versionPanacheRepo.find("documentId = ?1 and filePath is not null", documentId)
                .list()
                .map(list -> list.stream().map(po -> po.getFilePath()).toList());
    }

    @Override
    public Uni<List<String>> findVersionFilePathsByDocumentIds(java.util.Collection<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return versionPanacheRepo.find("documentId in ?1 and filePath is not null", documentIds)
                .list()
                .map(list -> list.stream().map(po -> po.getFilePath()).toList());
    }

    @Override
    public Uni<Void> deleteAllByIds(java.util.Collection<String> documentIds,
                                    java.util.Collection<String> statuses) {
        if (documentIds == null || documentIds.isEmpty() || statuses == null || statuses.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        // 每条语句带 status 守卫（子表经 documents 子查询联动）：
        // 并发窗口内状态翻转为不可删的文档整体跳过，避免"文档存活而子表被清"的半删状态
        String cascadeIds = "select d.id from DocumentPO d where d.id in ?1 and d.status in ?2";
        return metadataPanacheRepo.delete("documentId in (" + cascadeIds + ")", documentIds, statuses)
                .flatMap(v -> versionPanacheRepo.delete("documentId in (" + cascadeIds + ")", documentIds, statuses))
                .flatMap(v -> panacheRepo.delete("id in ?1 and status in ?2", documentIds, statuses))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> archiveVersion(DocumentVersion version) {
        DocumentVersionPO po = new DocumentVersionPO();
        po.setDocumentId(version.documentId());
        po.setVersionNumber(version.versionNumber());
        po.setFilePath(version.filePath());
        po.setFileSize(version.fileSize());
        po.setMimeType(version.mimeType());
        po.setWasPublished(version.wasPublished());
        po.setStatus(version.status() != null ? version.status() : DocumentVersion.STATUS_PUBLISHED);
        po.setCreatedBy(version.createdBy());
        po.setCreatedAt(version.createdAt());
        // upsert：当前版曾被版本级操作物化过时行已存在（PK 冲突），改为按主键覆盖更新
        return versionPanacheRepo.findById(new DocumentVersionPO.DocumentVersionPK(po.getDocumentId(), po.getVersionNumber()))
                .flatMap(existing -> existing == null
                        ? versionPanacheRepo.persist(po).replaceWithVoid()
                        : versionPanacheRepo.getSession().flatMap(session -> session.merge(po))
                                .replaceWithVoid());
    }

    @Override
    public Uni<Boolean> updateVersionStatus(String documentId, String versionNumber, String status) {
        // where 幂等守卫：仅 PUBLISHED（含历史 NULL 视同 PUBLISHED）可下线；重复归档/废弃同一版本静默成功
        return versionPanacheRepo.update(
                        "status = ?3 where documentId = ?1 and versionNumber = ?2 "
                                + "and (status = 'PUBLISHED' or status is null)",
                        documentId, versionNumber, status)
                .map(updated -> updated > 0);
    }

    @Override
    public Uni<Void> delete(DocumentId id) {
        String docId = id.value();
        // 级联删除：先子表（元数据/版本历史），后主表；均在同一事务内执行
        return metadataPanacheRepo.delete("documentId = ?1", docId)
                .flatMap(v -> versionPanacheRepo.delete("documentId = ?1", docId))
                .flatMap(v -> panacheRepo.deleteById(docId))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> deleteDocumentKeepVersions(DocumentId id) {
        String docId = id.value();
        // 删除 documents 行 + document_metadata，保留 document_versions 历史行（当前版本级删除专用）
        return metadataPanacheRepo.delete("documentId = ?1", docId)
                .flatMap(v -> panacheRepo.deleteById(docId))
                .replaceWithVoid();
    }

    @Override
    public Uni<DocumentVersion> findVersion(String documentId, String versionNumber) {
        return versionPanacheRepo.findById(new DocumentVersionPO.DocumentVersionPK(documentId, versionNumber))
                .map(po -> po == null ? null : new DocumentVersion(
                        po.getDocumentId(), po.getVersionNumber(), po.getFilePath(),
                        po.getFileSize(), po.getMimeType(), po.getWasPublished(),
                        po.getStatus() != null ? po.getStatus() : DocumentVersion.STATUS_PUBLISHED,
                        po.getCreatedBy(), po.getCreatedAt()));
    }

    @Override
    public Uni<Void> markProcessing(DocumentId id) {
        // where process_status IN ('PENDING','FAILED') 守卫：发布/重试消息发送成功 = 新处理周期开始，
        // 上一周期遗留 FAILED 随新周期清掉（否则新周期 COMPLETED 回调被终态守卫当过期跳过，
        // 文档冻结在"已发布+向量化失败"）；COMPLETED 不在守卫内（回调先到的竞态保护），PROCESSING 幂等
        return panacheRepo.getSession()
                .flatMap(session -> session.createNativeQuery(
                        "update documents set process_status='PROCESSING', process_error=null, process_error_code=null "
                                + "where id=?1 and process_status in ('PENDING','FAILED')")
                        .setParameter(1, id.value())
                        .executeUpdate())
                .replaceWithVoid();
    }

    /** 版本兜底解析允许的生命周期：归档（ARCHIVED）/已废弃（DEPRECATED，回收站语义）不可经旧路径解析 */
    private static final List<String> VERSION_FALLBACK_EXCLUDED_STATUSES = List.of("ARCHIVED", "DEPRECATED");

    @Override
    public Uni<Boolean> deleteVersion(String documentId, String versionNumber) {
        return versionPanacheRepo.delete("documentId = ?1 and versionNumber = ?2", documentId, versionNumber)
                .map(deleted -> deleted > 0);
    }

    @Override
    public Uni<java.util.Map<String, String>> findIdsByFilePaths(java.util.Collection<String> filePaths) {
        return findRefsByFilePaths(filePaths)
                .map(refs -> {
                    java.util.Map<String, String> result = new java.util.HashMap<>();
                    refs.forEach((path, ref) -> result.put(path, ref.documentId()));
                    return result;
                });
    }

    @Override
    public Uni<java.util.Map<String, PathRef>> findRefsByFilePaths(java.util.Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return Uni.createFrom().item(java.util.Map.of());
        }
        // 两段解析：documents 优先（当前路径）；未命中路径回退 document_versions（历史版本路径）。
        // 背景：版本覆盖后 documents.file_path 已更新为新路径，旧版归档路径仅在 document_versions，
        // 问答链路（ChatDocsSourceResolverImpl）/recycleReplacedFile 按旧路径解析不到 documentId。
        // 版本号：documents 命中=current_version（最新版）；document_versions 命中=version_number，
        // 供前端按对应版本下载（点历史版本引用不再拿到当前版内容）。
        return panacheRepo.find("filePath in ?1", filePaths).list()
                .map(list -> {
                    java.util.Map<String, PathRef> result = new java.util.HashMap<>();
                    for (DocumentPO po : list) {
                        if (po.getFilePath() != null) {
                            // 当前路径命中：versionStatus 镜像文档生命周期（当前版无版本行，
                            // 前端按 versionStatus 显示，需恒为有界已知值）
                            result.putIfAbsent(po.getFilePath(),
                                    new PathRef(po.getId(), po.getCurrentVersion(), po.getStatus()));
                        }
                    }
                    return result;
                })
                .flatMap(current -> {
                    java.util.Set<String> misses = new java.util.HashSet<>(filePaths);
                    misses.removeAll(current.keySet());
                    if (misses.isEmpty()) {
                        return Uni.createFrom().item(current);
                    }
                    // 兜底段：document_versions 命中后再经 documents 守卫「未归档、未删除」
                    // （ARCHIVED/DEPRECATED 文档不可经旧版本路径解析）
                    return versionPanacheRepo.find("filePath in ?1", misses).list()
                            .flatMap(vpos -> {
                                if (vpos.isEmpty()) {
                                    return Uni.createFrom().item(current);
                                }
                                java.util.Set<String> versionDocIds = vpos.stream()
                                        .map(vp -> vp.getDocumentId())
                                        .collect(java.util.stream.Collectors.toSet());
                                return panacheRepo
                                        .find("id in ?1 and status not in ?2", versionDocIds,
                                                VERSION_FALLBACK_EXCLUDED_STATUSES)
                                        .list()
                                        .map(alive -> {
                                            java.util.Set<String> aliveIds = alive.stream()
                                                    .map(po -> po.getId())
                                                    .collect(java.util.stream.Collectors.toSet());
                                            // 同路径多版本行取最新归档（createdAt 最大者胜）
                                            java.util.Map<String, DocumentVersionPO> latest = new java.util.HashMap<>();
                                            for (DocumentVersionPO vp : vpos) {
                                                if (!aliveIds.contains(vp.getDocumentId()) || vp.getFilePath() == null) {
                                                    continue;
                                                }
                                                DocumentVersionPO prev = latest.get(vp.getFilePath());
                                                if (prev == null
                                                        || (vp.getCreatedAt() != null && prev.getCreatedAt() != null
                                                        && vp.getCreatedAt().isAfter(prev.getCreatedAt()))
                                                        || (vp.getCreatedAt() != null && prev.getCreatedAt() == null)) {
                                                    latest.put(vp.getFilePath(), vp);
                                                }
                                            }
                                            latest.forEach((path, vp) -> {
                                                if (!current.containsKey(path)) {
                                                    current.put(path, new PathRef(vp.getDocumentId(), vp.getVersionNumber(),
                                                            vp.getStatus() != null ? vp.getStatus()
                                                                    : DocumentVersion.STATUS_PUBLISHED));
                                                }
                                            });
                                            return current;
                                        });
                            });
                });
    }
}
