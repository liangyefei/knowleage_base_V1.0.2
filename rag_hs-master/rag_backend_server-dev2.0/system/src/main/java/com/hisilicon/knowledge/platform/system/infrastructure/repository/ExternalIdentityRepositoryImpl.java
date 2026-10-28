package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.domain.repository.ExternalIdentityRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.po.ExternalIdentityPO;
import com.hisilicon.knowledge.platform.system.infrastructure.po.UserPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;

/**
 * 外部身份关联仓储实现（{@code external_identities}）。
 *
 * <p>同时实现 {@link PanacheRepositoryBase}：本仓储是 {@link ExternalIdentityPO} 的唯一数据访问入口，
 * 绑定查询与写入都在这里完成；用户名通过 {@link UserPanacheRepository} 按 {@code user_id} 回查
 * （用户的读侧契约在 {@code UserRepository}，本实现不绕过领域接口去改用户）。</p>
 */
@ApplicationScoped
public class ExternalIdentityRepositoryImpl
        implements ExternalIdentityRepository, PanacheRepositoryBase<ExternalIdentityPO, String> {

    private final UserPanacheRepository userPanacheRepository;

    public ExternalIdentityRepositoryImpl(UserPanacheRepository userPanacheRepository) {
        this.userPanacheRepository = userPanacheRepository;
    }

    @Override
    public Uni<ExternalIdentity> findBySourceAndExternalId(String source, String externalId) {
        return find("source = ?1 and externalId = ?2", source, externalId).firstResult()
                .flatMap(po -> po == null
                        ? Uni.createFrom().nullItem()
                        : toDomain(po));
    }

    @Override
    public Uni<ExternalIdentity> findByUserId(String userId) {
        return find("userId", userId).firstResult()
                .flatMap(po -> po == null
                        ? Uni.createFrom().nullItem()
                        : toDomain(po));
    }

    @Override
    public Uni<Void> create(ExternalIdentity identity) {
        ExternalIdentityPO po = new ExternalIdentityPO();
        po.setId(identity.id());
        po.setUserId(identity.userId());
        po.setSource(identity.source());
        po.setExternalId(identity.externalId());
        po.setIdpName(identity.idpName());
        po.setCreatedAt(LocalDateTime.now());
        return persist(po).replaceWithVoid();
    }

    /**
     * PO → 领域投影：顺带解析 {@code users.username}。
     * <p>关联用户被硬删除时 username 为 null，由调用方按"外部账号已失效"处理（fail-closed）。</p>
     */
    private Uni<ExternalIdentity> toDomain(ExternalIdentityPO po) {
        return userPanacheRepository.findById(po.getUserId())
                .map((UserPO user) -> new ExternalIdentity(
                        po.getId(),
                        po.getUserId(),
                        user == null ? null : user.getUsername(),
                        po.getSource(),
                        po.getExternalId(),
                        po.getIdpName()));
    }
}
