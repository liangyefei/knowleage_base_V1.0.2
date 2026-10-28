package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.domain.entity.McpToken;
import com.hisilicon.knowledge.platform.system.domain.repository.McpTokenRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.mapper.McpTokenMapper;
import com.hisilicon.knowledge.platform.system.infrastructure.po.McpTokenPO;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 令牌仓储实现。
 *
 * <p>读写分离口径与模块其他仓储一致：只读方法自持 {@code @WithSession}，
 * 写方法不加事务注解，由调用它的应用服务（{@code @WithTransaction}）提供事务边界。</p>
 *
 * @author hisilicon
 */
@ApplicationScoped
public class McpTokenRepositoryImpl implements McpTokenRepository {

    private final McpTokenPanacheRepository panacheRepo;
    private final McpTokenMapper mapper = Mappers.getMapper(McpTokenMapper.class);

    public McpTokenRepositoryImpl(McpTokenPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Void> save(McpToken token) {
        McpTokenPO po = mapper.toPO(token);
        return panacheRepo.persist(po).replaceWithVoid();
    }

    @Override
    @WithSession
    public Uni<Optional<McpToken>> findByJti(String jti) {
        if (jti == null || jti.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return panacheRepo.findById(jti).map(po -> Optional.ofNullable(mapper.toDomain(po)));
    }

    @Override
    @WithSession
    public Uni<List<McpToken>> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        return panacheRepo.find("username = ?1 order by createdAt desc", username)
                .list()
                .map(pos -> pos.stream().map(mapper::toDomain).toList());
    }

    @Override
    public Uni<Integer> markRevoked(String jti, LocalDateTime revokedTime) {
        return panacheRepo.update("revokedAt = :revokedAt where jti = :jti and revokedAt is null",
                Map.of("revokedAt", revokedTime, "jti", jti));
    }
}