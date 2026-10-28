package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.HotWordPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.HotWordPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBasePanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.HotWordItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.HotWordOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.HotWordStats;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.panache.common.Page;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 热门词查询服务（CQRS 查询侧）
 * <p>列表排序口径固定：sortOrder 升序、updatedAt 降序；
 * {@link #listEnabled} 跨所选知识库返回启用中热门词：
 * 总额度 = max(4, 所选知识库数)，名额尽量均衡（2 库各约 2 条），库数超过 4 时每库 1 条。</p>
 */
@ApplicationScoped
public class HotWordQueryService {

    private static final Logger logger = LoggerFactory.getLogger(HotWordQueryService.class);

    /** listEnabled 基础额度（总额度 = max(该值, 所选知识库数)） */
    private static final int BASE_QUOTA = 4;

    private static final Short STATUS_ENABLED = 1;

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 1000;

    private final HotWordPanacheRepository hotWordPanacheRepository;
    private final KnowledgeBasePanacheRepository kbRepo;

    public HotWordQueryService(HotWordPanacheRepository hotWordPanacheRepository,
                               KnowledgeBasePanacheRepository kbRepo) {
        this.hotWordPanacheRepository = hotWordPanacheRepository;
        this.kbRepo = kbRepo;
    }

    /**
     * 分页查询热门词（keyword 模糊 / kbId / status 全部可选）
     *
     * @param keyword  内容关键字（模糊，可选）
     * @param kbId     知识库 ID（可选）
     * @param status   状态（可选）
     * @param page     页码（默认 1）
     * @param pageSize 每页条数（默认 20，最大 1000）
     * @return 分页结果
     */
    @WithSession
    public Uni<PageResult<HotWordItem>> list(String keyword, String kbId, Short status,
                                             Integer page, Integer pageSize) {
        int pageNo = (page == null || page < 1) ? DEFAULT_PAGE : page.intValue();
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("1 = 1");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and lower(content) like :keyword");
            params.put("keyword", "%" + keyword.toLowerCase(Locale.ROOT) + "%");
        }
        if (kbId != null && !kbId.isBlank()) {
            where.append(" and kbId = :kbId");
            params.put("kbId", kbId);
        }
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }

        String orderBy = " order by sortOrder asc, updatedAt desc";
        logger.info("Hot word paged query: where={}, page={}, pageSize={}", where, pageNo, size);

        return hotWordPanacheRepository.find(where + orderBy, params)
                .page(Page.of(pageNo - 1, size))
                .list()
                .flatMap(pos -> hotWordPanacheRepository.count(where.toString(), params)
                        .flatMap(total -> {
                            long totalPage = size == 0 ? 0 : (total + size - 1) / size;
                            List<HotWordItem> items = pos.stream()
                                    .map(po -> new HotWordItem(po.getId(), po.getContent(), po.getKbId(),
                                            null, po.getSortOrder(), po.getStatus(), po.getUpdatedAt()))
                                    .toList();
                            // 批量补齐知识库名称（管理列表展示用），查不到的置 null 不影响主数据
                            Map<String, Object> kbParams = Map.of("kbIds",
                                    items.stream().map(HotWordItem::kbId).distinct().toList());
                            return kbRepo.find("id in :kbIds", kbParams).list()
                                    .map(kbs -> {
                                        Map<String, String> kbNames = kbs.stream()
                                                .collect(Collectors.toMap(kb -> kb.getId(), kb -> kb.getName()));
                                        return items.stream()
                                                .map(item -> new HotWordItem(item.id(), item.content(),
                                                        item.kbId(), kbNames.get(item.kbId()),
                                                        item.sortOrder(), item.status(), item.updatedAt()))
                                                .toList();
                                    })
                                    .map(filled -> new PageResult<>(pageNo, size, totalPage, total, filled));
                        }));
    }

    /**
     * 热门词统计（总数 / 启用中 / 已停用）
     *
     * @param kbIds 知识库 ID 列表（可选：null 或空 = 全部；多选时只统计所选库）
     * @return 统计结果（disabled = total - enabled）
     */
    @WithSession
    public Uni<HotWordStats> stats(List<String> kbIds) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("1 = 1");
        if (kbIds != null && !kbIds.isEmpty()) {
            where.append(" and kbId in :kbIds");
            params.put("kbIds", kbIds);
        }
        logger.info("Hot word stats query: kbIds.size={}", kbIds == null ? 0 : kbIds.size());

        return hotWordPanacheRepository.count(where.toString(), params)
                .flatMap(total -> {
                    Map<String, Object> enabledParams = new HashMap<>(params);
                    enabledParams.put("s", STATUS_ENABLED);
                    return hotWordPanacheRepository.count(where + " and status = :s", enabledParams)
                            .map(enabled -> new HotWordStats(total, enabled, total - enabled));
                });
    }

    /**
     * 查询启用中的热门词下拉选项（跨所选知识库）
     * <p>名额分配尽量均衡：总额度 = max(4, 所选知识库去重数)。按全局顺序轮询各库轮流取 1 条，
     * 直至额度用尽或取完 —— 库数 ≤4 时各库名额接近均分（2 库各 2 条），库数 >4 时每库 1 条；
     * 某库无启用词或名额用尽则跳过。</p>
     *
     * @param kbIds 知识库 ID 列表（非空，由接口层校验）
     * @return 选项列表（sortOrder 升序、updatedAt 降序）
     */
    @WithSession
    public Uni<List<HotWordOption>> listEnabled(List<String> kbIds) {
        List<String> distinctKbIds = kbIds.stream().distinct().toList();
        Map<String, Object> params = Map.of(
                "status", STATUS_ENABLED,
                "kbIds", distinctKbIds);
        logger.info("Query enabled hot word options: kbIds.size={}, distinct={}", kbIds.size(), distinctKbIds.size());

        return hotWordPanacheRepository
                .find("status = :status and kbId in :kbIds order by sortOrder asc, updatedAt desc", params)
                .list()
                .map(pos -> {
                    // 全局有序（sortOrder asc, updatedAt desc），分组保持组内有序
                    Map<String, List<HotWordPO>> byKb = pos.stream()
                            .collect(Collectors.groupingBy(po -> po.getKbId(),
                                    LinkedHashMap::new, Collectors.toList()));
                    // 按全局顺序轮询各库：每轮各库取其下一条（组内有序），额度用尽或取完为止
                    int quota = Math.max(BASE_QUOTA, distinctKbIds.size());
                    List<HotWordPO> selected = new ArrayList<>(quota);
                    List<List<HotWordPO>> kbGroups = new ArrayList<>(byKb.values());
                    int[] cursor = new int[kbGroups.size()];
                    while (selected.size() < quota) {
                        boolean picked = false;
                        for (int g = 0; g < kbGroups.size() && selected.size() < quota; g++) {
                            List<HotWordPO> group = kbGroups.get(g);
                            if (cursor[g] < group.size()) {
                                selected.add(group.get(cursor[g]++));
                                picked = true;
                            }
                        }
                        if (!picked) {
                            break;
                        }
                    }
                    // 整体按排序口径重排后返回
                    return selected.stream()
                            .sorted(Comparator
                                    .comparing((HotWordPO po) -> po.getSortOrder())
                                    .thenComparing(po -> po.getUpdatedAt(), Comparator.reverseOrder()))
                            .map(po -> new HotWordOption(po.getId(), po.getContent(), po.getKbId(), po.getSortOrder()))
                            .toList();
                });
    }
}
