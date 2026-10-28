package com.hisilicon.knowledge.platform.llm.sensitive.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveLexiconPO;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveTagPO;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveLexiconPanacheRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveTagPanacheRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.wordfile.SensitiveWordExportRow;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveWordItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 敏感词词库查询服务（CQRS 查询侧）
 */
@ApplicationScoped
public class SensitiveWordQueryService {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveWordQueryService.class);

    private final SensitiveLexiconPanacheRepository panacheRepo;
    private final SensitiveTagPanacheRepository tagPanacheRepo;

    public SensitiveWordQueryService(SensitiveLexiconPanacheRepository panacheRepo,
                                     SensitiveTagPanacheRepository tagPanacheRepo) {
        this.panacheRepo = panacheRepo;
        this.tagPanacheRepo = tagPanacheRepo;
    }

    /**
     * 分页查询词库（tagId 可选：不传查全部；可按词模糊、状态过滤）
     */
    @WithSession
    public Uni<PageResult<SensitiveWordItem>> search(String tagId, String word, Short status,
                                                     Integer page, Integer pageSize) {
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 1000);

        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (tagId != null && !tagId.isBlank()) {
            where.append(" and tagId = :tagId");
            params.put("tagId", tagId.trim());
        }
        if (word != null && !word.isBlank()) {
            where.append(" and lower(word) like :word");
            params.put("word", "%" + word.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }

        logger.info("Sensitive word search: tagId={}, word={}, status={}, page={}, pageSize={}",
                tagId, word, status, p, ps);

        // list() 与 count() 必须串行（flatMap 链），勿并发——实体 list 并发有 LoadContexts 风险
        return panacheRepo.find(where.toString(), params)
                .page(io.quarkus.panache.common.Page.of(p - 1, ps))
                .list()
                .flatMap(pos -> panacheRepo.count(where.toString(), params)
                        .flatMap(total -> resolveTagLabels(pos)
                                .map(labels -> toPageResult(p, ps, total, pos, labels))));
    }

    /**
     * 批量取词所属标签名称（一次 in 查询，避免逐行 N+1）；软删/缺失标签的词防御性返回空串
     */
    private Uni<Map<String, String>> resolveTagLabels(List<SensitiveLexiconPO> pos) {
        if (pos.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        Set<String> tagIds = pos.stream().map(SensitiveLexiconPO::getTagId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return tagPanacheRepo.find("status != :deleted and id in :ids",
                        Map.of("deleted", SensitiveTagEntity.STATUS_DELETED, "ids", tagIds))
                .list()
                .map(tags -> tags.stream()
                        .collect(Collectors.toMap(SensitiveTagPO::getId, SensitiveTagPO::getTextLabel, (a, b) -> a)));
    }

    /**
     * 导出用：跨标签取全量词（tagId 可选，可按词模糊过滤），携带标签信息，
     * 按 标签名+词 排序；软删标签的词不应存在（删除时级联），防御性跳过
     */
    @WithSession
    public Uni<List<SensitiveWordExportRow>> listForExport(String tagId, String word) {
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (tagId != null && !tagId.isBlank()) {
            where.append(" and tagId = :tagId");
            params.put("tagId", tagId.trim());
        }
        if (word != null && !word.isBlank()) {
            where.append(" and lower(word) like :word");
            params.put("word", "%" + word.trim().toLowerCase(Locale.ROOT) + "%");
        }

        logger.info("Sensitive word export query: tagId={}, word={}", tagId, word);

        return panacheRepo.find(where.toString() + " order by word", params)
                .list()
                .flatMap(pos -> {
                    if (pos.isEmpty()) {
                        return Uni.createFrom().item(List.<SensitiveWordExportRow>of());
                    }
                    Set<String> tagIds = pos.stream().map(SensitiveLexiconPO::getTagId)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    return tagPanacheRepo.find("status != :deleted and id in :ids",
                                    Map.of("deleted", SensitiveTagEntity.STATUS_DELETED, "ids", tagIds))
                            .list()
                            .map(tags -> toExportRows(pos, tags));
                });
    }

    private List<SensitiveWordExportRow> toExportRows(List<SensitiveLexiconPO> pos, List<SensitiveTagPO> tags) {
        Map<String, SensitiveTagPO> tagById = tags.stream()
                .collect(Collectors.toMap(SensitiveTagPO::getId, Function.identity(), (a, b) -> a));
        return pos.stream()
                .filter(po -> tagById.containsKey(po.getTagId()))
                .map(po -> {
                    SensitiveTagPO tag = tagById.get(po.getTagId());
                    return new SensitiveWordExportRow(tag.getTextLabel(), tag.getLabelIdentification(),
                            tag.getStatus(), po.getWord(), po.getStatus());
                })
                .sorted(java.util.Comparator.comparing(SensitiveWordExportRow::textLabel)
                        .thenComparing(SensitiveWordExportRow::word))
                .toList();
    }

    private PageResult<SensitiveWordItem> toPageResult(long page, long pageSize, long total,
                                                       List<SensitiveLexiconPO> pos, Map<String, String> labels) {
        long totalPage = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(page, pageSize, totalPage, total,
                pos.stream().map(po -> toItem(po, labels.getOrDefault(po.getTagId(), ""))).toList());
    }

    private SensitiveWordItem toItem(SensitiveLexiconPO po, String textLabel) {
        return new SensitiveWordItem(po.getId(), po.getTagId(), textLabel, po.getWord(), po.getStatus(), po.getCreatedAt(),
                po.getCreatedBy(), po.getRemarks());
    }
}
