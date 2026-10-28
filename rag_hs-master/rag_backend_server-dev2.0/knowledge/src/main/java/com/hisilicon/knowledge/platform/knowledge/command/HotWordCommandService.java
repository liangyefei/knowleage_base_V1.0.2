package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.HotWordId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.HotWordRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.HotWordDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.exception.HotWordEnabledLimitException;
import com.hisilicon.knowledge.platform.knowledge.exception.HotWordNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.HotWordPanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 热门词命令服务（应用服务层）
 * <p>业务规则：同知识库内 content 唯一；每知识库启用中至多 4 条。
 * 启用计数检查在事务内先取 {@code pg_advisory_xact_lock(hashtext(kbId))} 再计数，
 * 防止并发创建/启用导致的超限。</p>
 */
@ApplicationScoped
public class HotWordCommandService {

    private static final Logger logger = LoggerFactory.getLogger(HotWordCommandService.class);

    /**
     * 每知识库启用中热门词上限
     */
    private static final long ENABLED_LIMIT = 4;

    private final HotWordRepository hotWordRepository;
    private final HotWordPanacheRepository hotWordPanacheRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public HotWordCommandService(HotWordRepository hotWordRepository,
                                 HotWordPanacheRepository hotWordPanacheRepository,
                                 KnowledgeBaseRepository knowledgeBaseRepository) {
        this.hotWordRepository = hotWordRepository;
        this.hotWordPanacheRepository = hotWordPanacheRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 创建热门词
     * <p>事务内：advisory lock → content 唯一校验 → 启用数上限校验（status=1 时）→ persist。</p>
     *
     * @param operatorId 操作员用户 ID
     * @param command    创建命令
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> create(String operatorId, CreateHotWordCommand command) {
        return ensureKbExists(command.kbId())
                .chain(v -> acquireKbAdvisoryLock(command.kbId()))
                .chain(() -> hotWordRepository.existsByKbIdAndContent(command.kbId(), command.content())
                        .map(exists -> {
                            if (Boolean.TRUE.equals(exists)) {
                                logger.warn("Create hot word failed, duplicate content: kbId={}, operatorId={}", command.kbId(), operatorId);
                                throw new HotWordDuplicateException("同知识库下热门词内容已存在");
                            }
                            return null;
                        }))
                .chain(() -> ensureEnabledLimit(command.kbId(), command.status()))
                .chain(() -> hotWordRepository.persist(HotWordEntity.create(
                        command.content(), command.kbId(), command.sortOrder(), command.status(), operatorId)))
                .replaceWithVoid();
    }

    /**
     * 更新热门词（内容 / 所属知识库 / 排序号 / 状态）
     * <p>content 或 kbId 变化时重新做唯一校验（排除自身：变化后必与原值不同，无需排除）。
     * 目标状态=启用且（原状态非启用或 kbId 变化）时，先 advisory lock + 启用数上限校验。</p>
     *
     * @param command 更新命令
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> update(UpdateHotWordCommand command) {
        return findExisting(command.id())
                .chain(entity -> {
                    boolean identityChanged = !entity.getContent().equals(command.content())
                            || !entity.getKbId().equals(command.kbId());
                    // kbId 变化时校验目标知识库存在且未删除，避免脏 id 落到 FK 约束报 500
                    Uni<Void> kbGuard = entity.getKbId().equals(command.kbId())
                            ? Uni.createFrom().voidItem()
                            : ensureKbExists(command.kbId());
                    Uni<Boolean> uniqueness = identityChanged
                            ? hotWordRepository.existsByKbIdAndContent(command.kbId(), command.content())
                            : Uni.createFrom().item(Boolean.FALSE);
                    boolean enabling = HotWordEntity.STATUS_ENABLED.intValue() == command.status().intValue()
                            && (HotWordEntity.STATUS_ENABLED.intValue() != entity.getStatus().intValue()
                                || !entity.getKbId().equals(command.kbId()));
                    Uni<Void> statusGuard = enabling
                            ? acquireKbAdvisoryLock(command.kbId())
                                .chain(v -> ensureEnabledLimit(command.kbId(), command.status()))
                            : Uni.createFrom().voidItem();
                    return kbGuard
                            .chain(v -> uniqueness)
                            .map(exists -> {
                                if (Boolean.TRUE.equals(exists)) {
                                    logger.warn("Update hot word failed, duplicate content: id={}, kbId={}", command.id(), command.kbId());
                                    throw new HotWordDuplicateException("同知识库下热门词内容已存在");
                                }
                                return null;
                            })
                            .chain(v -> statusGuard)
                            .chain(v -> hotWordRepository.merge(
                                    entity.withKbId(command.kbId())
                                            .withContent(command.content())
                                            .withSortOrder(command.sortOrder())
                                            .withStatus(command.status())));
                })
                .replaceWithVoid();
    }

    /**
     * 删除热门词（物理删除）
     *
     * @param command ID 命令
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> delete(HotWordIdCommand command) {
        return findExisting(command.id())
                .chain(hotWordRepository::delete)
                .replaceWithVoid();
    }

    /**
     * 变更热门词状态（启用/停用）
     * <p>目标状态与现值相同直接返回（幂等）；启用时 advisory lock + 启用数上限校验。</p>
     *
     * @param command 状态变更命令（status 合法性 0/1 由接口层校验）
     * @return 完成
     */
    @WithTransaction
    public Uni<Void> changeStatus(ChangeHotWordStatusCommand command) {
        return findExisting(command.id())
                .chain(entity -> {
                    if (entity.getStatus().intValue() == command.status().intValue()) {
                        return Uni.createFrom().item(entity);
                    }
                    Uni<Void> guard = HotWordEntity.STATUS_ENABLED.intValue() == command.status().intValue()
                            ? acquireKbAdvisoryLock(entity.getKbId())
                            .chain(v -> hotWordRepository.countEnabledByKbId(entity.getKbId()))
                            .map(count -> {
                                if (count >= ENABLED_LIMIT) {
                                    logger.warn("Enable hot word failed, enabled count has reached the limit: kbId={}, count={}",
                                            entity.getKbId(), count);
                                    throw new HotWordEnabledLimitException(
                                            "该知识库启用中的热门词已达上限 " + ENABLED_LIMIT + " 条");
                                }
                                return null;
                            })
                            : Uni.createFrom().voidItem();
                    return guard.chain(v -> {
                        HotWordEntity updated = HotWordEntity.STATUS_ENABLED.intValue() == command.status().intValue()
                                ? entity.enable()
                                : entity.disable();
                        return hotWordRepository.merge(updated);
                    });
                })
                .replaceWithVoid();
    }

    // ======================== 内部工具 ========================

    private Uni<HotWordEntity> findExisting(String id) {
        return hotWordRepository.findById(HotWordId.of(id))
                .map(opt -> opt.orElseThrow(() -> {
                    logger.warn("Hot word not found: id={}", id);
                    return new HotWordNotFoundException("热门词不存在");
                }));
    }

    /**
     * 校验知识库存在且未删除（软删视同不存在），否则抛 A0430 业务异常而非落到 FK 约束报 500
     */
    private Uni<Void> ensureKbExists(String kbId) {
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> {
                    logger.warn("Owning knowledge base not found: kbId={}", kbId);
                    return new KnowledgeBaseNotFoundException("所属知识库不存在");
                })
                .flatMap(kb -> KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())
                        ? Uni.createFrom().voidItem()
                        : Uni.createFrom().failure(new KnowledgeBaseNotFoundException("所属知识库不存在或已删除")));
    }

    /**
     * 取当前事务内该知识库的 advisory 事务锁，串行化同一 kb 的启用计数检查
     */
    private Uni<Void> acquireKbAdvisoryLock(String kbId) {
        return hotWordPanacheRepository.getSession()
                .flatMap(session -> session.createNativeQuery(
                                "select pg_advisory_xact_lock(hashtext(:kbId))")
                        .setParameter("kbId", kbId)
                        .getSingleResult())
                .replaceWithVoid();
    }

    /**
     * status=1 时校验该知识库启用数上限（调用前须已持有 advisory lock）
     */
    private Uni<Void> ensureEnabledLimit(String kbId, Short status) {
        if (status == null || HotWordEntity.STATUS_ENABLED.intValue() != status.intValue()) {
            return Uni.createFrom().voidItem();
        }
        return hotWordRepository.countEnabledByKbId(kbId)
                .map(count -> {
                    if (count >= ENABLED_LIMIT) {
                        logger.warn("Create and enable hot word failed, enabled count has reached the limit: kbId={}, count={}", kbId, count);
                        throw new HotWordEnabledLimitException(
                                "该知识库启用中的热门词已达上限 " + ENABLED_LIMIT + " 条");
                    }
                    return null;
                })
                .replaceWithVoid();
    }
}
