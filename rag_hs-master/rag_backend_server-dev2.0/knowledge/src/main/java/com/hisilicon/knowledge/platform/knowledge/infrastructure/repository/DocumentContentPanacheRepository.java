package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentContentPO;

import java.time.LocalDateTime;

/**
 * Panache 数据访问层（文档内容检索表）
 */
@ApplicationScoped
public class DocumentContentPanacheRepository implements PanacheRepositoryBase<DocumentContentPO, String> {

    /**
     * 响应式连接池（可选注入）：抽取落库专用通道。
     * <p>orm 关闭场景（单测 quarkus.hibernate-orm.enabled=false）无此 bean，经 {@link Instance}
     * 可选注入避免 Arc 校验失败；生产环境可解析。</p>
     */
    @Inject
    Instance<Pool> poolInstance;

    /** 抽取结果原子 upsert（单语句，不经 Hibernate session） */
    private static final String UPSERT_SQL = """
            insert into document_contents
                (document_id, file_path, content_text, content_status, extracted_at)
            values ($1, $2, $3, $4, $5)
            on conflict (document_id) do update
                set file_path = excluded.file_path,
                    content_text = excluded.content_text,
                    content_status = excluded.content_status,
                    extracted_at = excluded.extracted_at
            """;

    /**
     * 抽取结果落库（事务性 upsert，按 documentId 覆盖写）。
     * <p>⚠️ 生产路径必须走 {@link Pool} 连接池直连，<b>绝不能</b>经 Hibernate session：
     * fire-and-forget 索引（{@code DocumentContentIndexService#indexAsync}）在外层上传/编辑事务
     * <b>在途时</b>启动，而 Quarkus Hibernate Reactive 在同一 Vert.x duplicated context 上
     * 复用同一个 session——无论 {@code Panache.getSession()} 还是
     * {@code SessionFactory.withTransaction} 都会 join 它，在外层事务中间插队执行查询 →
     * 同一 session 两组 JDBC 结果处理交错 → "Illegal pop() with non-matching
     * JdbcValuesSourceProcessingState"，并连带打挂请求（2026-09-21 csv 上传实测，
     * SessionFactory.withTransaction 修复无效后复测确认）。连接池连接与 Hibernate session
     * 是两条独立通道，机制上不可能交错。orm 不可用时（单测）回退上下文 session 路径，
     * 该场景无真实 DB 不涉及交错。</p>
     */
    public Uni<Void> saveExtracted(String documentId, String filePath,
                                   String contentText, String contentStatus) {
        if (poolInstance.isResolvable()) {
            return poolInstance.get().preparedQuery(UPSERT_SQL)
                    .execute(Tuple.of(documentId, filePath, contentText, contentStatus,
                            LocalDateTime.now()))
                    .replaceWithVoid();
        }
        return Panache.getSession()
                .flatMap(session -> session.find(DocumentContentPO.class, documentId)
                        .flatMap(po -> {
                            if (po == null) {
                                po = new DocumentContentPO();
                                po.setDocumentId(documentId);
                            }
                            po.setFilePath(filePath);
                            po.setContentText(contentText);
                            po.setContentStatus(contentStatus);
                            po.setExtractedAt(LocalDateTime.now());
                            return session.persist(po);
                        }))
                .replaceWithVoid();
    }

    /**
     * 回填任务行级原子认领（见 DocumentContentBackfillJob）：把行置为 PENDING（等值新路径），
     * RETURNING 命中 = 认领成功；更新 0 行 = 他人已认领/等值终态行无需处理。
     * <p>短事务执行（upsert 单语句原子，无长锁）。</p>
     *
     * @return true = 认领成功
     */
    @WithTransaction
    public Uni<Boolean> claimPending(String documentId, String filePath,
                                     LocalDateTime now, LocalDateTime staleBefore) {
        String sql = """
                insert into document_contents
                    (document_id, file_path, content_text, content_status, extracted_at)
                values (?1, ?2, null, 'PENDING', ?3)
                on conflict (document_id) do update
                    set file_path = excluded.file_path,
                        content_text = null,
                        content_status = 'PENDING',
                        extracted_at = excluded.extracted_at
                    where document_contents.file_path is distinct from excluded.file_path
                       or (document_contents.content_status = 'PENDING'
                           and document_contents.extracted_at < ?4)
                returning document_id
                """;
        return Panache.getSession()
                .flatMap(session -> session.createNativeQuery(sql, String.class)
                        .setParameter(1, documentId)
                        .setParameter(2, filePath)
                        .setParameter(3, now)
                        .setParameter(4, staleBefore)
                        .getSingleResultOrNull())
                .map(returned -> returned != null);
    }
}
