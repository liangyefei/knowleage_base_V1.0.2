package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.KnowledgeBaseMapper;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

/**
 * 知识库仓储实现
 */
@ApplicationScoped
public class KnowledgeBaseRepositoryImpl implements KnowledgeBaseRepository {

    private final KnowledgeBasePanacheRepository panacheRepo;
    private final KnowledgeBaseMapper mapper = Mappers.getMapper(KnowledgeBaseMapper.class);

    public KnowledgeBaseRepositoryImpl(KnowledgeBasePanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<KnowledgeBaseEntity> findById(KnowledgeBaseId id) {
        return panacheRepo.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Uni<KnowledgeBaseEntity> findByIdForUpdate(KnowledgeBaseId id) {
        // 复用当前事务 session 加悲观行锁（SELECT ... FOR NO KEY UPDATE），锁持续到事务提交/回滚。
        // 注意：不能用 session.find(PO, id, PESSIMISTIC_WRITE)——Hibernate Reactive 会另行生成
        // 内部锁查询 "select id from ... for no key update" 且不绑定 id 参数，执行时报
        // "expected 1 parameter but actual 0"（测试环境删除知识库 500 的根因）。
        // 改走 HQL 查询 + 查询级锁：参数经 Query 链路绑定，锁子句由翻译器内联到同一条 select。
        return panacheRepo.getSession()
                .flatMap(session -> session.createQuery(
                                "from KnowledgeBasePO po where po.id = ?1", KnowledgeBasePO.class)
                        .setParameter(1, id.value())
                        .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                        .getSingleResultOrNull())
                .map(mapper::toDomain);
    }

    @Override
    public Uni<KnowledgeBaseEntity> findByName(String name) {
        // 只查未删除（status=1），软删除的记录不占用名称，允许重建同名知识库
        return panacheRepo.find("status = 1 and name = ?1", name).firstResult()
                .map(mapper::toDomain);
    }

    @Override
    public Uni<KnowledgeBaseEntity> persist(KnowledgeBaseEntity entity) {
        return panacheRepo.persist(mapper.toPO(entity))
                .map(po -> entity);
    }

    @Override
    public Uni<KnowledgeBaseEntity> update(KnowledgeBaseEntity entity) {
        // 实体不可变，更新通过 merge 将新 PO 合并到会话
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(entity)))
                .map(po -> entity);
    }

    @Override
    public Uni<Void> markProcessing(KnowledgeBaseId id) {
        // where process_status='PENDING' 守卫：不覆盖终态，PROCESSING 幂等；清空旧失败原因
        return panacheRepo.getSession()
                .flatMap(session -> session.createNativeQuery(
                        "update knowledge_bases set process_status='PROCESSING', process_error=null, process_error_code=null "
                                + "where id=?1 and process_status='PENDING'")
                        .setParameter(1, id.value())
                        .executeUpdate())
                .replaceWithVoid();
    }
}
