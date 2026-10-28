package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionId;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.UploadSessionRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动 Mock — 替换 {@link UploadSessionRepositoryImpl}
 * <p>内存 Map 存储会话（persist/update/findById 真实读写），tus 端点契约测试用。</p>
 */
public class MockUploadSessionRepository extends UploadSessionRepositoryImpl {

    private static final Map<String, UploadSession> store = new ConcurrentHashMap<>();

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockUploadSessionRepository() {
        super(null);
    }

    public static void reset() {
        store.clear();
    }

    public static UploadSession get(String sessionId) {
        return store.get(sessionId);
    }

    @Override
    public Uni<UploadSession> findById(UploadSessionId id) {
        return Optional.ofNullable(store.get(id.value()))
                .map(v -> Uni.createFrom().item(v))
                .orElseGet(() -> Uni.createFrom().nullItem());
    }

    @Override
    public Uni<UploadSession> persist(UploadSession entity) {
        store.put(entity.getId().value(), entity);
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<UploadSession> update(UploadSession entity) {
        store.put(entity.getId().value(), entity);
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<List<UploadSession>> findIncompleteByBatch(String batchId) {
        return Uni.createFrom().item(store.values().stream()
                .filter(s -> s.getBatchId().equals(batchId))
                .filter(s -> !s.getStatus().isTerminal())
                .toList());
    }

    @Override
    public Uni<List<UploadSession>> findActiveByKbId(String kbId) {
        return Uni.createFrom().item(store.values().stream()
                .filter(s -> s.getKbId().equals(kbId))
                .filter(s -> !s.getStatus().isTerminal())
                .toList());
    }

    @Override
    public Uni<List<UploadSession>> findActiveByTargetDocument(String targetDocumentId, String operatorId) {
        return Uni.createFrom().item(store.values().stream()
                .filter(s -> targetDocumentId.equals(s.getTargetDocumentId()))
                .filter(s -> operatorId.equals(s.getCreatedBy()))
                .filter(s -> !s.getStatus().isTerminal())
                .toList());
    }

    @Override
    public Uni<List<UploadSession>> findExpiredIncomplete(LocalDateTime now) {
        return Uni.createFrom().item(List.of());
    }

    @Override
    public Uni<Integer> bumpEntriesDone(String sessionId) {
        return Uni.createFrom().item(1);
    }

    @Override
    public Uni<List<UploadSession>> findStaleProcessing(LocalDateTime threshold) {
        return Uni.createFrom().item(List.of());
    }
}
