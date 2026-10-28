package com.hisilicon.knowledge.platform.system.domain.repository;

import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;

/**
 * 用户仓储接口
 */
public interface UserRepository {

    /**
     * 根据用户名查询用户
     */
    Uni<UserEntity> findByUsername(String username);

    /**
     * 更新用户登录时间
     */
    Uni<Integer> updateLoginTime(String username, LocalDateTime lastLogin);
}
