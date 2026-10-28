package com.example.project.user.domain.repository;

import com.example.project.user.domain.entity.UserEntity;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;

/**
 * 用户仓储接口
 */
public interface UserRepository {
    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户信息实体
     */
    Uni<UserEntity> findByUsername(String username);

    /**
     * 更新用户登录时间
     * @param username 用户名
     * @param lastLogin 最近登录时间
     * @return 影响的数据条数
     */
    Uni<Integer> updateLoginTime(String username, LocalDateTime lastLogin);
}
