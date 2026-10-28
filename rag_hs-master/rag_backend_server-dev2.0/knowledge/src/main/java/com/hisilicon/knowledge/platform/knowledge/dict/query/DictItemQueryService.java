package com.hisilicon.knowledge.platform.knowledge.dict.query;

import com.hisilicon.knowledge.platform.commons.dict.po.DictItemPO;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictItemPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.dict.query.dto.DictItemItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 字典条目查询服务（CQRS 查询侧）
 */
@ApplicationScoped
public class DictItemQueryService {

    private static final Logger logger = LoggerFactory.getLogger(DictItemQueryService.class);

    private final DictItemPanacheRepository panacheRepo;

    public DictItemQueryService(DictItemPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    /**
     * 查询字典条目列表（typeCode 必传，可按状态筛选）
     * <p>供前端下拉框使用：通常传 {@code status=1} 只取活跃条目，按 sortOrder 升序。</p>
     *
     * @param typeCode 字典类型编码（必传）
     * @param status   状态筛选（null = 全部，1 = 正常，0 = 已删除）
     * @return 条目列表
     */
    @WithSession
    public Uni<List<DictItemItem>> list(String typeCode, Short status) {
        StringBuilder where = new StringBuilder("typeCode = ?1");
        if (status != null) {
            where.append(" and status = ?2");
        }
        where.append(" order by sortOrder, value");

        logger.info("Dict item list: typeCode={}, status={}", typeCode, status);

        return (status != null
                ? panacheRepo.find(where.toString(), typeCode, status)
                : panacheRepo.find(where.toString(), typeCode))
                .list()
                .map(items -> items.stream().map(this::toItem).toList());
    }

    private DictItemItem toItem(DictItemPO po) {
        return new DictItemItem(po.getId(), po.getTypeCode(), po.getLabel(), po.getValue(),
                po.getSortOrder(), po.getStatus(), po.getCreatedAt(), po.getUpdatedAt());
    }
}
