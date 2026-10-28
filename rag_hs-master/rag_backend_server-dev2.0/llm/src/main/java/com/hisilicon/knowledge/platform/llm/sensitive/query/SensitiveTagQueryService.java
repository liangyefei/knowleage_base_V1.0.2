package com.hisilicon.knowledge.platform.llm.sensitive.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveTagPO;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveLexiconPanacheRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveTagPanacheRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagItem;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagStatistics;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveTagWordCount;
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
 * 敏感词标签查询服务（CQRS 查询侧）
 */
@ApplicationScoped
public class SensitiveTagQueryService {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveTagQueryService.class);

    private final SensitiveTagPanacheRepository panacheRepo;
    private final SensitiveLexiconPanacheRepository lexiconPanacheRepo;

    public SensitiveTagQueryService(SensitiveTagPanacheRepository panacheRepo,
                                    SensitiveLexiconPanacheRepository lexiconPanacheRepo) {
        this.panacheRepo = panacheRepo;
        this.lexiconPanacheRepo = lexiconPanacheRepo;
    }

    /**
     * 分页查询标签（关键字模糊匹配 textLabel/labelIdentification，可按状态筛选；默认不含软删）
     *
     * @param page     页码（null/<1 = 1）
     * @param pageSize 每页条数（null/<1 = 20，上限 1000）
     * @param keyword  关键字（可选）
     * @param status   状态筛选（null = 全部未删除）
     */
    @WithSession
    public Uni<PageResult<SensitiveTagItem>> search(Integer page, Integer pageSize, String keyword, Short status) {
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 1000);

        StringBuilder where = new StringBuilder("status != 0");
        Map<String, Object> params = new HashMap<>();

        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (lower(textLabel) like :kw or lower(labelIdentification) like :kw)");
            params.put("kw", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }

        logger.info("Sensitive tag search: where={}, page={}, pageSize={}", where, p, ps);

        // list() 与 count() 必须串行（flatMap 链），勿并发——实体 list 并发有 LoadContexts 风险
        return panacheRepo.find(where.toString(), params)
                .page(io.quarkus.panache.common.Page.of(p - 1, ps))
                .list()
                .flatMap(tags -> panacheRepo.count(where.toString(), params)
                        .map(total -> toPageResult(p, ps, total, tags)));
    }

    /**
     * 全部启用标签下拉列表
     */
    @WithSession
    public Uni<List<SensitiveTagItem>> listActive() {
        return panacheRepo.find("status = ?1 order by createdAt", (short) 1)
                .list()
                .map(tags -> tags.stream().map(this::toItem).toList());
    }

    /**
     * 分类统计（管理页侧边栏）：全部分类词数 + 每个分类词数。
     *
     * <p>口径：标签取未删除（status != 0，含停用分类，否则管理页看不到停用分类）；
     * 词计数含启用与停用（词为硬删，无软删态，与词列表页默认展示一致）；
     * 无词分类保留 wordCount=0；{@code total} 恒等于各分类之和（聚合子查询限定未删除
     * 标签，孤儿词不计入，杜绝"总数对不上明细"）。不引入缓存：标签数十、词数千，
     * 一次聚合查询成本可忽略，缓存反而要处理敏感词变更后的失效。</p>
     */
    @WithSession
    public Uni<SensitiveTagStatistics> statistics() {
        logger.info("Sensitive tag statistics query");
        return panacheRepo.find("status != ?1 order by createdAt", SensitiveTagEntity.STATUS_DELETED)
                .list()
                .flatMap(tags -> countWordsByTagId().map(counts -> toStatistics(tags, counts)));
    }

    /**
     * 按标签聚合词数（单条 group by 查询，避免逐分类 N+1 计数）
     */
    private Uni<Map<String, Long>> countWordsByTagId() {
        return lexiconPanacheRepo.getSession()
                .flatMap(session -> session.createQuery(
                                "select l.tagId, count(l.id) from SensitiveLexiconPO l "
                                        + "where l.tagId in (select t.id from SensitiveTagPO t where t.status <> ?1) "
                                        + "group by l.tagId",
                                Object[].class)
                        .setParameter(1, SensitiveTagEntity.STATUS_DELETED)
                        .getResultList())
                .map(rows -> {
                    Map<String, Long> counts = new HashMap<>();
                    for (Object[] row : rows) {
                        counts.put((String) row[0], ((Number) row[1]).longValue());
                    }
                    return counts;
                });
    }

    /**
     * 组装统计结果：标签顺序即返回顺序（createdAt 升序）；无词分类补 0；total = 各分类之和
     */
    static SensitiveTagStatistics toStatistics(List<SensitiveTagPO> tags, Map<String, Long> counts) {
        List<SensitiveTagWordCount> items = tags.stream()
                .map(po -> new SensitiveTagWordCount(po.getId(), po.getTextLabel(), po.getLabelIdentification(), po.getStatus(),
                        counts.getOrDefault(po.getId(), 0L)))
                .toList();
        long total = items.stream().mapToLong(SensitiveTagWordCount::wordCount).sum();
        return new SensitiveTagStatistics(total, items);
    }

    private PageResult<SensitiveTagItem> toPageResult(long page, long pageSize, long total, List<SensitiveTagPO> tags) {
        long totalPage = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(page, pageSize, totalPage, total,
                tags.stream().map(this::toItem).toList());
    }

    private SensitiveTagItem toItem(SensitiveTagPO po) {
        return new SensitiveTagItem(po.getId(), po.getTextLabel(), po.getLabelIdentification(), po.getRemarks(),
                po.getStatus(), po.getCreatedAt(), po.getUpdatedAt());
    }
}
