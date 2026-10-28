package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentVersionPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentVersionOption;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档版本列表查询服务（版本对比步骤 3：版本选择下拉）
 * <p>返回当前版（documents 行，isCurrent=true）+ 全部历史版（document_versions 版本行，
 * 归档时间倒序）。版本行状态 ARCHIVED/DEPRECATED 照实返回，由前端决定是否可选——
 * 对比历史正是版本对比的场景，不做 A0508 拦截（与 download/detail 不同）。</p>
 * <p>去重口径与 {@link DocumentQueryService#listDocuments} 一致：currentVersion 不与
 * 归档版本行重复（防御性兜底存量/脏数据）。</p>
 */
@ApplicationScoped
public class DocumentVersionQueryService {

    private final DocumentPanacheRepository documentPanacheRepository;
    private final DocumentVersionPanacheRepository documentVersionPanacheRepository;

    public DocumentVersionQueryService(DocumentPanacheRepository documentPanacheRepository,
                                       DocumentVersionPanacheRepository documentVersionPanacheRepository) {
        this.documentPanacheRepository = documentPanacheRepository;
        this.documentVersionPanacheRepository = documentVersionPanacheRepository;
    }

    /**
     * 查询文档的可选版本列表（当前版在前，历史版按归档时间倒序）。
     * <p>仅可见文档可读：孤儿文档（categoryId 指向已删/不存在/跨库类别）视同不存在，
     * 与 listDocuments/navTree 口径一致。</p>
     *
     * @param documentId 文档 ID
     * @return 版本选项列表（至少含当前版一行）
     */
    @WithSession
    public Uni<List<DocumentVersionOption>> listVersions(String documentId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        // 实体查询串行：同一 session 上并发实体查询破坏 LoadContexts（同 DocumentQueryService）。
        return documentPanacheRepository.findVisibleById(documentId).firstResult()
                .onItem().ifNull().failWith(() -> new DocumentNotFoundException(appMessages.documentNotFound()))
                .flatMap(po -> documentVersionPanacheRepository
                        .find("documentId = ?1 order by createdAt desc", po.getId()).list()
                        .map(vpos -> buildOptions(po, vpos)));
    }

    /** 组装版本选项：当前版一行 + 历史版各行（去重 + 排序） */
    private static List<DocumentVersionOption> buildOptions(DocumentPO po, List<DocumentVersionPO> vpos) {
        List<DocumentVersionOption> options = new ArrayList<>();
        if (po.getCurrentVersion() != null && !po.getCurrentVersion().isBlank()) {
            options.add(new DocumentVersionOption(po.getCurrentVersion(), true,
                    po.getStatus(), null, po.getFileSize(), po.getMimeType(), null, po.getCreatedBy(), po.getCreatedAt()));
        }
        // 历史版：归档时间倒序；去重守卫与 DocumentQueryService.pageVersionRows 一致
        Set<String> seen = new HashSet<>();
        if (po.getCurrentVersion() != null && !po.getCurrentVersion().isBlank()) {
            seen.add(po.getCurrentVersion());
        }
        List<DocumentVersionPO> ordered = new ArrayList<>(vpos);
        ordered.sort(Comparator.comparing(v -> v.getCreatedAt(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        for (DocumentVersionPO vp : ordered) {
            if (vp.getVersionNumber() == null || vp.getVersionNumber().isBlank() || !seen.add(vp.getVersionNumber())) {
                continue;
            }
            options.add(new DocumentVersionOption(vp.getVersionNumber(), false,
                    vp.getStatus() != null ? vp.getStatus() : "PUBLISHED",
                    vp.getWasPublished(), vp.getFileSize(), vp.getMimeType(), vp.getChangeSummary(),
                    vp.getCreatedBy() != null ? vp.getCreatedBy() : po.getCreatedBy(),
                    vp.getCreatedAt() != null ? vp.getCreatedAt() : po.getCreatedAt()));
        }
        return List.copyOf(options);
    }
}
