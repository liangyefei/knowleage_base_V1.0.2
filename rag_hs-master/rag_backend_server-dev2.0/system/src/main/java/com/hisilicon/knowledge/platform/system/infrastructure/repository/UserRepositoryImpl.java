package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.mapper.UserMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户仓储实现
 */
@ApplicationScoped
public class UserRepositoryImpl implements UserRepository {

    private final UserPanacheRepository panacheRepo;
    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    public UserRepositoryImpl(UserPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<UserEntity> findByUsername(String username) {
        return panacheRepo.find("username", username).firstResult()
                .map(mapper::toDomain);
    }

    @Override
    public Uni<Integer> updateLoginTime(String username, LocalDateTime lastLogin) {
        return panacheRepo.update("lastLogin = :lastLogin where username = :username",
                Map.of("username", username, "lastLogin", lastLogin));
    }
}
