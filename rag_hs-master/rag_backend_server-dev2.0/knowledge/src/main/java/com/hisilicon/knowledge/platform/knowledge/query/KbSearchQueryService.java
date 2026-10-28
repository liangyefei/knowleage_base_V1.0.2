package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBasePanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbSearchItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbSearchQuery;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库搜索查询服务（CQRS 查询侧）
 * <p>聚合计数采用 Panache Repository 查询，避免依赖 Hibernate 原生 Session，便于测试环境替换。</p>
 */
@ApplicationScoped
public class KbSearchQueryService {

    private static final Logger logger = LoggerFactory.getLogger(KbSearchQueryService.class);

    private final KnowledgeBasePanacheRepository kbPanacheRepository;
    private final DocumentPanacheRepository documentPanacheRepository;
    private final CategoryPanacheRepository categoryPanacheRepository;

    public KbSearchQueryService(KnowledgeBasePanacheRepository kbPanacheRepository,
                                DocumentPanacheRepository documentPanacheRepository,
                                CategoryPanacheRepository categoryPanacheRepository) {
        this.kbPanacheRepository = kbPanacheRepository;
        this.documentPanacheRepository = documentPanacheRepository;
        this.categoryPanacheRepository = categoryPanacheRepository;
    }

    /**
     * 分页搜索知识库（名称模糊、状态精准、创建者模糊），附带文档数/分类数聚合
     */
    @WithSession
    public Uni<PageResult<KbSearchItem>> search(KbSearchQuery query) {
        // 1) 动态查询条件
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("status = :status");
        params.put("status", query.status());

        if (query.kbName() != null && !query.kbName().isBlank()) {
            where.append(" and lower(name) like :kbName");
            params.put("kbName", "%" + query.kbName().toLowerCase(Locale.ROOT) + "%");
        }
        if (query.createdBy() != null && !query.createdBy().isBlank()) {
            where.append(" and createdBy like :createdBy");
            params.put("createdBy", "%" + query.createdBy() + "%");
        }

        logger.info("Knowledge base search: where={}, page={}, pageSize={}", where, query.page(), query.pageSize());

        // 2) 分页查询 + 总数
        return kbPanacheRepository.find(where.toString(), params)
                .page(io.quarkus.panache.common.Page.of(query.page() - 1, query.pageSize()))
                .list()
                .flatMap(kbs -> kbPanacheRepository.count(where.toString(), params)
                        .flatMap(total -> enrich(kbs, total, query)));
    }

    /**
     * 逐 KB 并发聚合文档数/分类数，组装分页结果
     */
    private Uni<PageResult<KbSearchItem>> enrich(List<KnowledgeBasePO> kbs, long total, KbSearchQuery query) {
        if (kbs.isEmpty()) {
            return Uni.createFrom().item(
                    new PageResult<>(query.page(), query.pageSize(), 0, total, List.of()));
        }

        List<Uni<KbSearchItem>> itemUnis = kbs.stream()
                .map(this::buildItem)
                .toList();

        return Uni.combine().all().unis(itemUnis).with(this::toItemList)
                .map(items -> {
                    long totalPage = (total + query.pageSize() - 1) / query.pageSize();
                    return new PageResult<>(query.page(), query.pageSize(), totalPage, total, items);
                });
    }

    /** combine 结果统一收敛为 {@code List<KbSearchItem>}（instanceof 校验，不用裸强转，G.TYP.13） */
    private List<KbSearchItem> toItemList(List<?> items) {
        List<KbSearchItem> out = new java.util.ArrayList<>(items.size());
        for (Object raw : items) {
            if (raw instanceof KbSearchItem item) {
                out.add(item);
            }
        }
        return out;
    }

    private Uni<KbSearchItem> buildItem(KnowledgeBasePO kb) {
        // docCount 与 navTree documentCount 口径一致：仅根目录 + 活跃类别子树（排除孤儿文档）
        Uni<Long> docCount = documentPanacheRepository.count(
                "from DocumentPO d where d.kbId = ?1 and " + DocumentPanacheRepository.VISIBLE_IN_KB, kb.getId());
        Uni<Long> catCount = categoryPanacheRepository.count("kbId = ?1 and status = 1", kb.getId());
        return Uni.combine().all().unis(docCount, catCount).asTuple()
                .map(tuple -> new KbSearchItem(
                        kb.getId(),
                        kb.getName(),
                        kb.getDescription(),
                        kb.getStatus(),
                        kb.getCreatedBy(),
                        kb.getCreatedAt(),
                        tuple.getItem1(),
                        tuple.getItem2()));
    }
}
