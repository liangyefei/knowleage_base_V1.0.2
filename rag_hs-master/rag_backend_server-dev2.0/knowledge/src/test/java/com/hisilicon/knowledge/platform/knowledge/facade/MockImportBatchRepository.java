package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatch;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchId;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.ImportBatchRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动 Mock — 替换 {@link ImportBatchRepositoryImpl}
 * <p>内存 Map 存储，tus 端点契约测试用。预设批次经 {@link #put(ImportBatch)} 注入。</p>
 */
public class MockImportBatchRepository extends ImportBatchRepositoryImpl {

    private static final Map<String, ImportBatch> store = new ConcurrentHashMap<>();

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockImportBatchRepository() {
        super(null);
    }

    public static void reset() {
        store.clear();
    }

    public static void put(ImportBatch batch) {
        store.put(batch.getId().value(), batch);
    }

    @Override
    public Uni<ImportBatch> findById(ImportBatchId id) {
        return Optional.ofNullable(store.get(id.value()))
                .map(v -> Uni.createFrom().item(v))
                .orElseGet(() -> Uni.createFrom().nullItem());
    }

    @Override
    public Uni<ImportBatch> persist(ImportBatch entity) {
        store.put(entity.getId().value(), entity);
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<ImportBatch> update(ImportBatch entity) {
        store.put(entity.getId().value(), entity);
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<List<ImportBatch>> findByCreatedBy(String createdBy) {
        return Uni.createFrom().item(store.values().stream()
                .filter(b -> createdBy.equals(b.getCreatedBy()))
                .toList());
    }
}
