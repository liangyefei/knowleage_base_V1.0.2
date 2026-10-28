package com.example.project.user.infrastructure.repository;

import com.example.project.user.domain.entity.UserEntity;
import com.example.project.user.domain.repository.UserRepository;
import com.example.project.user.infrastructure.mapper.UserMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.Map;

@ApplicationScoped
public class UserRepositoryImpl implements UserRepository {
    private final UserPanacheRepository panacheRepo;
    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    public UserRepositoryImpl(UserPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<UserEntity> findByUsername(String username) {
        return panacheRepo.find("username", username).firstResult().map(mapper::toDomain);
    }

    @Override
    public Uni<Integer> updateLoginTime(String username, LocalDateTime lastLogin) {
        return panacheRepo.update("lastLogin = :lastLogin where username = :username",
                Map.of("username", username, "lastLogin", lastLogin));
    }
}
