package com.hisilicon.knowledge.platform.knowledge.dict.query;

import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictTypePanacheRepository;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictTypeItem;
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
 * 字典类型查询服务（CQRS 查询侧）
 */
@ApplicationScoped
public class DictTypeQueryService {

    private static final Logger logger = LoggerFactory.getLogger(DictTypeQueryService.class);

    private final DictTypePanacheRepository panacheRepo;

    public DictTypeQueryService(DictTypePanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    /**
     * 分页查询字典类型（可按状态筛选、关键字模糊匹配 typeCode/typeName）
     *
     * @param page     页码（null/<1 = 1）
     * @param pageSize 每页条数（null/<1 = 20，上限 1000）
     * @param keyword  关键字（匹配 typeCode/typeName，可选）
     * @param status   状态筛选（null = 全部，1 = 正常，0 = 已删除）
     * @return 分页结果
     */
    @WithSession
    public Uni<PageResult<DictTypeItem>> search(Integer page, Integer pageSize, String keyword, Short status) {
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 1000);

        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> params = new HashMap<>();

        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (lower(typeCode) like :kw or lower(typeName) like :kw)");
            params.put("kw", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }

        logger.info("Dict type search: where={}, page={}, pageSize={}", where, p, ps);

        // list() 与 count() 必须串行（flatMap 链），勿并发——实体 list 并发有 LoadContexts 风险
        return panacheRepo.find(where.toString(), params)
                .page(io.quarkus.panache.common.Page.of(p - 1, ps))
                .list()
                .flatMap(types -> panacheRepo.count(where.toString(), params)
                        .map(total -> toPageResult(p, ps, total, types)));
    }

    /**
     * 组装分页结果
     */
    private PageResult<DictTypeItem> toPageResult(long page, long pageSize, long total, List<DictTypePO> types) {
        long totalPage = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(page, pageSize, totalPage, total,
                types.stream().map(this::toItem).toList());
    }

    private DictTypeItem toItem(DictTypePO po) {
        return new DictTypeItem(po.getId(), po.getTypeCode(), po.getTypeName(), po.getControlledMetaKeys(),
                po.getStatus(), po.getIsPreset(), po.getCreatedAt(), po.getUpdatedAt());
    }
}
