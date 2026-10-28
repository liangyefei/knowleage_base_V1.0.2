package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.query.dto.CategoryListItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 类别查询服务（CQRS 查询侧，扁平列表）
 */
@ApplicationScoped
public class CategoryQueryService {

    private final CategoryPanacheRepository categoryPanacheRepository;

    public CategoryQueryService(CategoryPanacheRepository categoryPanacheRepository) {
        this.categoryPanacheRepository = categoryPanacheRepository;
    }

    /**
     * 列出知识库下指定父类别的直接子类别，附带 hasChildren 标记
     *
     * @param kbId     知识库 ID
     * @param parentId 父类别 ID（null 或空 = 根类别）
     * @return 子类别列表（按 sortOrder 升序）
     */
    @WithSession
    public Uni<List<CategoryListItem>> listCategories(String kbId, String parentId) {
        // 1) 一次查询拉取同 KB 所有活跃类别，避免 N+1
        return categoryPanacheRepository.list("kbId = ?1 and status = 1", kbId)
                .map(all -> {
                    // 2) 内存建 parentId 集合（用于 hasChildren 判断）
                    Set<String> parentIds = all.stream()
                            .map(po -> po.getParentId())
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                    // 3) 按 parentId 过滤 + 按 sortOrder 排序
                    return all.stream()
                            .filter(po -> {
                                if (parentId == null || parentId.isBlank()) {
                                    return po.getParentId() == null;
                                }
                                return parentId.equals(po.getParentId());
                            })
                            .sorted(Comparator.comparingInt(
                                    po -> po.getSortOrder() != null ? po.getSortOrder() : 0))
                            .map(po -> new CategoryListItem(
                                    po.getId(), po.getName(), parentIds.contains(po.getId()),
                                    po.getSortOrder() != null ? po.getSortOrder() : 0, po.getCreatedAt()))
                            .toList();
                });
    }
}
