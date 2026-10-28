package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBasePanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbOption;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 知识库下拉选项查询服务（CQRS 查询侧）
 * <p>返回其下至少存在一篇「已发布且向量化完成」文档的活跃就绪知识库，
 * 供前端下拉选择。查询约束（与 {@link KnowledgeBaseChatResolverImpl} 的核心约束一致，
 * 但不带 official/chip_model/docType/version 维度筛选）：</p>
 * <ul>
 *   <li>{@code kb.status = 1}（未软删）</li>
 *   <li>{@code kb.processStatus = 'COMPLETED'}（KB_CREATED 回调完成、向量库就绪）</li>
 *   <li>{@code exists documents where d.kbId = kb.id and d.status = 'PUBLISHED'
 *       and d.processStatus = 'COMPLETED'}（其下至少一篇已发布且向量化完成文档）</li>
 * </ul>
 * <p>按 {@code kb.createdAt} 升序返回，不分页、不做 ABAC 权限过滤
 * （与 navTree/search/dictOptions 既有端点一致）。仅返回 {@code kbId + kbName}。</p>
 */
@ApplicationScoped
public class KbOptionQueryService {

    private static final Logger logger = LoggerFactory.getLogger(KbOptionQueryService.class);

    private static final Short KB_STATUS_ACTIVE = 1;
    private static final String KB_PROCESS_COMPLETED = "COMPLETED";
    /** 文档生命周期：已发布（真正可检索前提之一：status=PUBLISHED 且 process_status=COMPLETED） */
    private static final String DOC_STATUS_PUBLISHED = "PUBLISHED";
    private static final String DOC_PROCESS_COMPLETED = "COMPLETED";

    private final KnowledgeBasePanacheRepository kbRepo;

    public KbOptionQueryService(KnowledgeBasePanacheRepository kbRepo) {
        this.kbRepo = kbRepo;
    }

    /**
     * 查询可检索知识库下拉选项（无参）
     *
     * @return 满足条件的知识库 {@code kbId + kbName} 列表（createdAt 升序）；无匹配返回空列表
     */
    @WithSession
    public Uni<List<KbOption>> listOptions() {
        String hql = "from KnowledgeBasePO kb"
                + " where kb.status = :kbStatus and kb.processStatus = :procStatus"
                + " and exists (select 1 from DocumentPO d"
                + " where d.kbId = kb.id and d.status = :docPub and d.processStatus = :docProc"
                // 排除孤儿文档（categoryId 指向已删/不存在/跨库类别）：仅剩孤儿已发布文档的
                // KB 不再视为可检索——与 navTree/搜索口径一致，孤儿内容不可经 UI 触达
                + " and " + DocumentPanacheRepository.VISIBLE_IN_KB + ")"
                + " order by kb.createdAt asc";
        Map<String, Object> params = Map.of(
                "kbStatus", KB_STATUS_ACTIVE,
                "procStatus", KB_PROCESS_COMPLETED,
                "docPub", DOC_STATUS_PUBLISHED,
                "docProc", DOC_PROCESS_COMPLETED);

        logger.info("Query selectable knowledge base options");
        return kbRepo.find(hql, params).list()
                .map(list -> list.stream()
                        .map(po -> new KbOption(po.getId(), po.getName()))
                        .toList());
    }
}
