package com.hisilicon.knowledge.platform.llm.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.InsightSettingsPO;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.QaInsightPO;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.InsightSettingsPanacheRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.QaInsightPanacheRepository;
import com.hisilicon.knowledge.platform.llm.query.dto.InsightListItem;
import com.hisilicon.knowledge.platform.llm.query.dto.InsightSettingsDTO;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答洞察查询服务（CQRS 查询侧）。
 */
@ApplicationScoped
public class InsightQueryService {

    private static final Logger logger = LoggerFactory.getLogger(InsightQueryService.class);

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final QaInsightPanacheRepository insightRepo;
    private final InsightSettingsPanacheRepository settingsRepo;

    public InsightQueryService(QaInsightPanacheRepository insightRepo,
                               InsightSettingsPanacheRepository settingsRepo) {
        this.insightRepo = insightRepo;
        this.settingsRepo = settingsRepo;
    }

    /**
     * 分页查询洞察。
     * @param statusFilter ALL=全部 / ANALYZED=已智能分析 / PENDING=未智能分析（默认 ANALYZED）
     */
    @WithSession
    public Uni<PageResult<InsightListItem>> listInsights(
            String keyword, String sensitive, String category,
            Integer confidenceMin, Integer confidenceMax,
            String rating, String statusFilter,
            Integer page, Integer pageSize) {

        int p = page == null || page < 1 ? DEFAULT_PAGE : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        StringBuilder hql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        // 状态筛选：默认 ANALYZED
        String status = (statusFilter == null || statusFilter.isBlank()) ? "ANALYZED" : statusFilter;
        if ("ALL".equals(status)) {
            // 不限状态
        } else if ("ANALYZED".equals(status) || "PENDING".equals(status)) {
            hql.append("status = ?").append(params.size() + 1);
            params.add(status);
        }

        if (keyword != null && !keyword.isBlank()) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("lower(question) like ?").append(params.size() + 1);
            params.add("%" + keyword.toLowerCase() + "%");
        }
        if ("SENSITIVE".equals(sensitive)) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("isSensitive = true");
        } else if ("NOT_SENSITIVE".equals(sensitive)) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("isSensitive = false");
        }
        if (category != null && !category.isBlank() && !"ALL".equals(category)) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("category = ?").append(params.size() + 1);
            params.add(category);
        }
        if (confidenceMin != null) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("confidence >= ?").append(params.size() + 1);
            params.add(confidenceMin);
        }
        if (confidenceMax != null) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("confidence <= ?").append(params.size() + 1);
            params.add(confidenceMax);
        }
        if ("LIKE".equals(rating) || "DISLIKE".equals(rating)) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("userRating = ?").append(params.size() + 1);
            params.add(rating);
        } else if ("NONE".equals(rating)) {
            if (!hql.isEmpty()) hql.append(" and ");
            hql.append("userRating is null");
        }

        return insightRepo.find(hql.toString(), Sort.descending("createdAt"), params.toArray())
                .page(Page.of(p - 1, size))
                .list()
                .chain(items -> insightRepo.count(hql.toString(), params.toArray())
                        .map(total -> {
                            int totalPage = (int) Math.ceil((double) total / size);
                            List<InsightListItem> dtos = items.stream()
                                    .map(this::toListItem)
                                    .toList();
                            return new PageResult<>(p, size, totalPage, total, dtos);
                        }));
    }

    /**
     * 查定时设置。
     */
    @WithSession
    public Uni<InsightSettingsDTO> getSettings() {
        return settingsRepo.findById(1)
                .map(po -> po == null
                        ? new InsightSettingsDTO("DAILY", "02:00", null, null)
                        : new InsightSettingsDTO(po.getScheduleType(), po.getScheduleTime(),
                                po.getScheduleDay(),
                                po.getUpdatedAt() == null ? null : po.getUpdatedAt().toString()));
    }

    private InsightListItem toListItem(QaInsightPO po) {
        return new InsightListItem(
                po.getId(),
                po.getQuestion(),
                po.getAnswer(),
                po.getCategory(),
                categoryLabel(po.getCategory()),
                po.getConfidence(),
                po.getIsSensitive(),
                po.getHitSensitiveWords(),
                po.getUserRating(),
                po.getCreatedAt());
    }

    private static String categoryLabel(String category) {
        if (category == null) return "无标记";
        return switch (category) {
            case "RESEARCH_PAIN" -> "研发难点";
            case "POTENTIAL_RISK" -> "潜在风险";
            case "POTENTIAL_NEED" -> "潜在需求";
            default -> category;
        };
    }
}
