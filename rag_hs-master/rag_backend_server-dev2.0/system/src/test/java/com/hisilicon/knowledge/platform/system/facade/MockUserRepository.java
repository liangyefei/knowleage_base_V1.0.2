package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.UserRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;

/**
 * 手动 Mock — 替换 {@link UserRepositoryImpl}
 * <p>通过静态字段 {@link #userToReturn} 控制返回结果。</p>
 */
public class MockUserRepository extends UserRepositoryImpl {

    /** null = not found, non-null = return this user */
    private static volatile UserEntity userToReturn;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockUserRepository() {
        super(null);
    }

    public static void setReturnUser(UserEntity user) {
        userToReturn = user;
    }

    @Override
    public Uni<UserEntity> findByUsername(String username) {
        UserEntity u = userToReturn;
        if (u == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(u);
    }

    @Override
    public Uni<Integer> updateLoginTime(String username, LocalDateTime lastLogin) {
        return Uni.createFrom().item(1);
    }
}
