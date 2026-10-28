package com.example.project.user.domain.entity;

import cn.hutool.jwt.JWTUtil;
import com.example.project.user.dto.TokenDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 领域层用户实体
 */
public class UserEntity {
    private static final Logger logger = LoggerFactory.getLogger(UserEntity.class);

    private UserId id;
    private String username;
    private String password;
    private LocalDateTime createdAt;
    private LocalDateTime lastLogin;

    private UserEntity(Builder builder) {
        this.id = builder.id;
        this.username = builder.username;
        this.password = builder.password;
        this.createdAt = builder.createdAt;
        this.lastLogin = builder.lastLogin;
    }

    /**
     * 领域行为：生成令牌
     */
    public TokenDTO generateToken() {
        logger.info("Generating token");
        String accessToken = JWTUtil.createToken(Map.of("user", username, "expire_time",
                System.currentTimeMillis() + 1000 * 60 * 60 * 24 * 15), "1234".getBytes());
        String refreshToken = JWTUtil.createToken(Map.of("token", accessToken, "expire_time",
                System.currentTimeMillis() + 2000 * 60 * 60 * 24 * 15), "1234".getBytes());

        this.lastLogin = LocalDateTime.now();
        return new TokenDTO(accessToken, refreshToken);
    }

    public UserId getId() { return id; }
    public String getUsername() { return username; }

    public String getPassword() {
        return password;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UserId id;
        private String username;
        private String password;
        private LocalDateTime createdAt;
        private LocalDateTime lastLogin;

        public Builder id(UserId id) { this.id = id; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder lastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; return this; }

        public UserEntity build() {
            if (id == null) throw new IllegalStateException("用户id不能为空");
            return new UserEntity(this);
        }
    }
}
