package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryMetadataPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 类别元数据仓储实现（category_metadata 表）
 */
@ApplicationScoped
public class CategoryMetadataRepositoryImpl implements CategoryMetadataRepository {

    private final CategoryMetadataPanacheRepository panacheRepo;

    public CategoryMetadataRepositoryImpl(CategoryMetadataPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Void> replaceAll(String categoryId, Map<String, String> metadata) {
        // 先删旧元数据（全量替换语义）
        return panacheRepo.delete("categoryId = ?1", categoryId)
                .flatMap(deleted -> {
                    if (metadata == null || metadata.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    // 再插入新元数据（串行链式 persist）
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    LocalDateTime now = LocalDateTime.now();
                    for (Map.Entry<String, String> entry : metadata.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        if (key == null || key.isBlank() || value == null || value.isBlank()) {
                            continue;
                        }
                        CategoryMetadataPO po = new CategoryMetadataPO();
                        po.setId(UUID.randomUUID().toString());
                        po.setCategoryId(categoryId);
                        po.setMetaKey(key.trim());
                        po.setMetaValue(value.trim());
                        po.setCreatedAt(now);
                        po.setUpdatedAt(now);
                        chain = chain.flatMap(v -> panacheRepo.persist(po).replaceWithVoid());
                    }
                    return chain;
                });
    }
}
