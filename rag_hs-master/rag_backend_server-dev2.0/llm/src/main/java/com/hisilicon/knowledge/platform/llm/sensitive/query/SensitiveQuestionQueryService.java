package com.hisilicon.knowledge.platform.llm.sensitive.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveQuestionLogPO;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveQuestionLogPanacheRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.query.dto.SensitiveQuestionItem;
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
 * 不合规提问记录查询服务（CQRS 查询侧）
 */
@ApplicationScoped
public class SensitiveQuestionQueryService {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveQuestionQueryService.class);

    private final SensitiveQuestionLogPanacheRepository panacheRepo;

    public SensitiveQuestionQueryService(SensitiveQuestionLogPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    /**
     * 分页查询不合规提问记录（可按账号模糊、标签标识、时间范围过滤，按请求时间倒序）
     *
     * @param query 查询条件对象（G.MET.01：>5 参数收敛为查询对象）
     */
    @WithSession
    public Uni<PageResult<SensitiveQuestionItem>> search(SensitiveQuestionQuery query) {
        int p = (query.page() == null || query.page() < 1) ? 1 : query.page();
        int ps = (query.pageSize() == null || query.pageSize() < 1)
                ? 20 : Math.min(query.pageSize(), 1000);

        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> params = new HashMap<>();

        String account = query.account();
        if (account != null && !account.isBlank()) {
            where.append(" and lower(account) like :account");
            params.put("account", "%" + account.trim().toLowerCase(Locale.ROOT) + "%");
        }
        String label = query.label();
        if (label != null && !label.isBlank()) {
            where.append(" and identification = :label");
            params.put("label", label.trim());
        }
        if (query.beginTime() != null) {
            where.append(" and requestTime >= :begin");
            params.put("begin", query.beginTime());
        }
        if (query.endTime() != null) {
            where.append(" and requestTime <= :end");
            params.put("end", query.endTime());
        }

        logger.info("Non-compliant question log search: account={}, label={}, page={}, pageSize={}", account, label, p, ps);

        // list() 与 count() 必须串行（flatMap 链），勿并发——实体 list 并发有 LoadContexts 风险
        return panacheRepo.find(where.toString() + " order by requestTime desc", params)
                .page(io.quarkus.panache.common.Page.of(p - 1, ps))
                .list()
                .flatMap(pos -> panacheRepo.count(where.toString(), params)
                        .map(total -> toPageResult(p, ps, total, pos)));
    }

    private PageResult<SensitiveQuestionItem> toPageResult(long page, long pageSize, long total,
                                                           List<SensitiveQuestionLogPO> pos) {
        long totalPage = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(page, pageSize, totalPage, total,
                pos.stream().map(this::toItem).toList());
    }

    private SensitiveQuestionItem toItem(SensitiveQuestionLogPO po) {
        return new SensitiveQuestionItem(po.getId(), po.getQuestion(), po.getAccount(), po.getWord(),
                po.getLabel(), po.getIdentification(), po.getRequestTime());
    }
}
