package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBasePanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NavTreeItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 导航树查询服务（CQRS 查询侧，左侧面板）
 * <p>两种模式：</p>
 * <ul>
 *   <li><b>懒加载</b>（keyword 空）：{@code navTree(null, "")} 返回全部活跃知识库及其根子类别（深度 = 1）；
 *       {@code navTree(parentId, "")} 返回指定类别的直接子类别。子级由前端带 {@code parentId} 再次调用加载。</li>
 *   <li><b>搜索</b>（keyword 非空）：返回<b>过滤后的完整树</b>（多级 children，一次性返回）——
 *       保留节点 = 命中节点 ∪ 其全部祖先链（路径）∪ 命中节点的全部后代；未命中兄弟裁剪。
 *       KB 名称命中时整个 KB 子树全量保留。过滤后节点计数按可见子树重算。</li>
 * </ul>
 * <p>每个节点的 documentCount/categoryCount 为该节点及可见后代的递归计数（文档不分状态，
 * 类别仅计活跃）。实现上每个知识库的类别/文档查询<b>串行</b>执行 —— 同一 session 上并发
 * 多个实体 {@code list()} 会破坏 Hibernate Reactive 的 LoadContexts 栈
 * （Illegal pop() with non-matching JdbcValuesSourceProcessingState）。</p>
 */
@ApplicationScoped
public class NavTreeQueryService {

    private static final Logger logger = LoggerFactory.getLogger(NavTreeQueryService.class);

    private final KnowledgeBasePanacheRepository kbPanacheRepository;
    private final CategoryPanacheRepository categoryPanacheRepository;
    private final DocumentPanacheRepository documentPanacheRepository;

    public NavTreeQueryService(KnowledgeBasePanacheRepository kbPanacheRepository,
                               CategoryPanacheRepository categoryPanacheRepository,
                               DocumentPanacheRepository documentPanacheRepository) {
        this.kbPanacheRepository = kbPanacheRepository;
        this.categoryPanacheRepository = categoryPanacheRepository;
        this.documentPanacheRepository = documentPanacheRepository;
    }

    /**
     * 导航树（懒加载 / 搜索）
     *
     * @param parentId 类别 ID；null 或空 = 根模式（全部活跃 KB + 根子类别）；
     *                 非空 = 该类别下的直接子类别（parentId 不存在或已删除时返回空列表）
     * @param keyword  名称模糊关键字（知识库 + 子类别，不区分大小写）；null/空白 = 不过滤
     * @return 导航树节点列表
     */
    @WithSession
    public Uni<List<NavTreeItem>> navTree(String parentId, String keyword) {
        String kw = normalizeKeyword(keyword);
        if (parentId == null || parentId.isBlank()) {
            if (kw.isEmpty()) {
                return navTreeRoot();
            }
            return navTreeSearchRoot(kw);
        }
        return navTreeChildren(parentId.trim(), kw);
    }

    // ======================== 懒加载根模式：全部 KB + 根子类别 ========================

    private Uni<List<NavTreeItem>> navTreeRoot() {
        return kbPanacheRepository.list("status = 1 order by createdAt")
                .flatMap(kbs -> {
                    if (kbs.isEmpty()) {
                        logger.info("Nav tree query: no active knowledge base");
                        return Uni.createFrom().item(List.of());
                    }
                    logger.info("Nav tree query: active knowledge base count={}", kbs.size());
                    return buildKbNodes(kbs, 0, new ArrayList<>());
                });
    }

    /**
     * 逐 KB 串行构建（同一 session 上并发实体 list() 会破坏 LoadContexts 栈）
     */
    private Uni<List<NavTreeItem>> buildKbNodes(List<KnowledgeBasePO> kbs, int index,
                                                List<NavTreeItem> acc) {
        if (index >= kbs.size()) {
            return Uni.createFrom().item(acc);
        }
        return buildKbNode(kbs.get(index))
                .flatMap(node -> {
                    acc.add(node);
                    return buildKbNodes(kbs, index + 1, acc);
                });
    }

    /** 单个 KB：类别列表 → 文档列表（串行），内存递归聚合（深度 = 1） */
    private Uni<NavTreeItem> buildKbNode(KnowledgeBasePO kb) {
        return categoryPanacheRepository.list("kbId = ?1 and status = 1", kb.getId())
                .flatMap(cats -> documentPanacheRepository.list("kbId = ?1", kb.getId())
                        .map(docs -> assembleKbNode(kb, cats, docs)));
    }

    // ======================== 搜索模式：过滤后的完整树 ========================

    private Uni<List<NavTreeItem>> navTreeSearchRoot(String kw) {
        return kbPanacheRepository.list("status = 1 order by createdAt")
                .flatMap(kbs -> {
                    if (kbs.isEmpty()) {
                        logger.info("Nav tree search: no active knowledge base, keyword={}", kw);
                        return Uni.createFrom().item(List.of());
                    }
                    logger.info("Nav tree search: active knowledge base count={}, keyword={}", kbs.size(), kw);
                    return buildSearchKbNodes(kbs, 0, new ArrayList<>(), kw);
                });
    }

    /** 逐 KB 串行：构建完整树 → 关键字过滤（不保留的 KB 不加入结果） */
    private Uni<List<NavTreeItem>> buildSearchKbNodes(List<KnowledgeBasePO> kbs, int index,
                                                      List<NavTreeItem> acc, String kw) {
        if (index >= kbs.size()) {
            return Uni.createFrom().item(acc);
        }
        return buildSearchKbNode(kbs.get(index), kw)
                .flatMap(node -> {
                    node.ifPresent(acc::add);
                    return buildSearchKbNodes(kbs, index + 1, acc, kw);
                });
    }

    private Uni<Optional<NavTreeItem>> buildSearchKbNode(KnowledgeBasePO kb, String kw) {
        return categoryPanacheRepository.list("kbId = ?1 and status = 1", kb.getId())
                .flatMap(cats -> documentPanacheRepository.list("kbId = ?1", kb.getId())
                        .map(docs -> filterByKeyword(assembleFullKbNode(kb, cats, docs), kw)));
    }

    // ======================== 懒加载模式：指定类别的下一层 ========================

    private Uni<List<NavTreeItem>> navTreeChildren(String parentId, String kw) {
        return categoryPanacheRepository.find("id = ?1 and status = 1", parentId).firstResult()
                .flatMap(parent -> {
                    if (parent == null) {
                        logger.info("Nav tree lazy load: category not found or deleted, parentId={}", parentId);
                        return Uni.createFrom().item(List.of());
                    }
                    // 复用同 KB 的类别/文档列表做递归计数（串行查询）
                    return categoryPanacheRepository.list("kbId = ?1 and status = 1", parent.getKbId())
                            .flatMap(cats -> documentPanacheRepository.list("kbId = ?1", parent.getKbId())
                                    .map(docs -> assembleChildren(parentId, cats, docs, kw)));
                });
    }

    // ======================== 内存组装 ========================

    /** 组装 KB 节点（懒加载：根子类别深度 = 1，children 为空） */
    private NavTreeItem assembleKbNode(KnowledgeBasePO kb, List<CategoryPO> cats, List<DocumentPO> docs) {
        Map<String, Long> docByCategory = buildDocDistribution(docs);
        Map<String, List<CategoryPO>> childrenByParent = buildChildrenMap(cats);

        List<NavTreeItem> rootChildren = new ArrayList<>();
        long totalDocs = docByCategory.getOrDefault(null, 0L);
        long totalCats = 0;
        List<CategoryPO> roots = childrenByParent.get(null);
        if (roots != null) {
            roots.sort(CATEGORY_ORDER);
            for (CategoryPO root : roots) {
                long[] counts = assembleCategoryNode(root, childrenByParent, docByCategory, rootChildren);
                totalDocs += counts[0];
                totalCats += counts[1];
            }
        }

        return new NavTreeItem(kb.getId(), NavTreeItem.TYPE_KB, kb.getName(), kb.getId(),
                totalDocs, totalCats,
                roots != null && !roots.isEmpty(), rootChildren);
    }

    /** 组装完整 KB 树（搜索模式：children 递归全量构建） */
    private NavTreeItem assembleFullKbNode(KnowledgeBasePO kb, List<CategoryPO> cats, List<DocumentPO> docs) {
        Map<String, Long> docByCategory = buildDocDistribution(docs);
        Map<String, List<CategoryPO>> childrenByParent = buildChildrenMap(cats);

        List<NavTreeItem> rootChildren = new ArrayList<>();
        long totalDocs = docByCategory.getOrDefault(null, 0L);
        long totalCats = 0;
        List<CategoryPO> roots = childrenByParent.get(null);
        if (roots != null) {
            roots.sort(CATEGORY_ORDER);
            for (CategoryPO root : roots) {
                NavTreeItem node = buildFullCategoryNode(root, childrenByParent, docByCategory);
                totalDocs += node.documentCount();
                totalCats += node.categoryCount();
                rootChildren.add(node);
            }
        }

        return new NavTreeItem(kb.getId(), NavTreeItem.TYPE_KB, kb.getName(), kb.getId(),
                totalDocs, totalCats,
                !rootChildren.isEmpty(), rootChildren);
    }

    /**
     * 递归组装完整类别节点（搜索模式：children 含全部后代）
     */
    private NavTreeItem buildFullCategoryNode(CategoryPO cat,
                                              Map<String, List<CategoryPO>> childrenByParent,
                                              Map<String, Long> docByCategory) {
        long docCount = docByCategory.getOrDefault(cat.getId(), 0L);
        long catCount = 1;
        List<NavTreeItem> kids = new ArrayList<>();
        List<CategoryPO> children = childrenByParent.get(cat.getId());
        if (children != null) {
            children.sort(CATEGORY_ORDER);
            for (CategoryPO child : children) {
                NavTreeItem sub = buildFullCategoryNode(child, childrenByParent, docByCategory);
                docCount += sub.documentCount();
                catCount += sub.categoryCount();
                kids.add(sub);
            }
        }
        return new NavTreeItem(cat.getId(), NavTreeItem.TYPE_CATEGORY, cat.getName(), cat.getKbId(),
                docCount, catCount, !kids.isEmpty(), kids);
    }

    /**
     * 组装指定类别的直接子类别（懒加载：children 恒为空列表，计数含全部后代）
     * <p>keyword 非空时在直接子级别中按名称过滤（命中节点保留，未命中裁剪）。</p>
     */
    private List<NavTreeItem> assembleChildren(String parentId, List<CategoryPO> cats,
                                               List<DocumentPO> docs, String kw) {
        Map<String, Long> docByCategory = buildDocDistribution(docs);
        Map<String, List<CategoryPO>> childrenByParent = buildChildrenMap(cats);

        List<CategoryPO> kids = childrenByParent.get(parentId);
        if (kids == null || kids.isEmpty()) {
            return List.of();
        }
        kids.sort(CATEGORY_ORDER);
        List<NavTreeItem> out = new ArrayList<>();
        for (CategoryPO kid : kids) {
            assembleCategoryNode(kid, childrenByParent, docByCategory, out);
        }
        if (kw.isEmpty()) {
            return out;
        }
        return out.stream()
                .filter(node -> node.name().toLowerCase(Locale.ROOT).contains(kw))
                .toList();
    }

    /**
     * 递归组装类别节点（懒加载：children 恒为空列表，计数含全部后代）
     *
     * @return {documentCount, categoryCount}
     */
    private long[] assembleCategoryNode(CategoryPO cat,
                                        Map<String, List<CategoryPO>> childrenByParent,
                                        Map<String, Long> docByCategory,
                                        List<NavTreeItem> output) {
        long docCount = docByCategory.getOrDefault(cat.getId(), 0L);
        long catCount = 1;
        List<CategoryPO> kids = childrenByParent.get(cat.getId());
        if (kids != null) {
            for (CategoryPO kid : kids) {
                long[] sub = assembleCategoryNode(kid, childrenByParent, docByCategory, null);
                docCount += sub[0];
                catCount += sub[1];
            }
        }
        if (output != null) {
            output.add(new NavTreeItem(cat.getId(), NavTreeItem.TYPE_CATEGORY, cat.getName(), cat.getKbId(),
                    docCount, catCount,
                    kids != null && !kids.isEmpty(), List.of()));
        }
        return new long[]{docCount, catCount};
    }

    /**
     * 关键字过滤（搜索模式）：命中节点全量保留；未命中节点仅当其子树有命中时作为
     * 祖先路径保留（children 裁剪为保留分支），计数按可见子树重算。
     *
     * @return 保留后的节点；子树无命中且自身未命中时为空
     */
    private Optional<NavTreeItem> filterByKeyword(NavTreeItem node, String kw) {
        if (node.name().toLowerCase(Locale.ROOT).contains(kw)) {
            return Optional.of(node); // 命中：整个子树全量保留（计数原值）
        }
        List<NavTreeItem> kept = new ArrayList<>();
        for (NavTreeItem child : node.children()) {
            filterByKeyword(child, kw).ifPresent(kept::add);
        }
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        // 路径节点：计数按可见子树重算（自身计数 = 原计数 - 全量子树计数 + 保留子树计数）
        long selfDoc = node.documentCount();
        long selfCat = node.categoryCount();
        for (NavTreeItem child : node.children()) {
            selfDoc -= child.documentCount();
            selfCat -= child.categoryCount();
        }
        long newDoc = selfDoc;
        long newCat = selfCat;
        for (NavTreeItem keptChild : kept) {
            newDoc += keptChild.documentCount();
            newCat += keptChild.categoryCount();
        }
        return Optional.of(new NavTreeItem(node.nodeId(), node.nodeType(), node.name(), node.kbId(),
                newDoc, newCat, !kept.isEmpty(), kept));
    }

    /** 规范化关键字：null/空白 → 空串；否则 trim + 小写 */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    /** 文档按 categoryId 分组计数（categoryId 为 null 的文档挂在 KB 根目录） */
    private static Map<String, Long> buildDocDistribution(List<DocumentPO> docs) {
        Map<String, Long> docByCategory = new HashMap<>();
        for (DocumentPO doc : docs) {
            docByCategory.merge(doc.getCategoryId(), 1L, Long::sum);
        }
        return docByCategory;
    }

    /** 类别 children 映射（parentId → 子类别列表） */
    private static Map<String, List<CategoryPO>> buildChildrenMap(List<CategoryPO> cats) {
        Map<String, List<CategoryPO>> childrenByParent = new HashMap<>();
        for (CategoryPO cat : cats) {
            childrenByParent.computeIfAbsent(cat.getParentId(), k -> new ArrayList<>()).add(cat);
        }
        return childrenByParent;
    }

    private static final Comparator<CategoryPO> CATEGORY_ORDER =
            Comparator.comparingInt(c -> c.getSortOrder() != null ? c.getSortOrder() : 0);
}
