package com.hisilicon.knowledge.platform.system.domain.entity;

import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import io.smallrye.jwt.build.Jwt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PrivateKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 领域层用户实体
 */
public class UserEntity {

    private static final Logger logger = LoggerFactory.getLogger(UserEntity.class);

    /** {@code users.source} 取值：平台本地账号（RSA 口令登录） */
    public static final String SOURCE_LOCAL = "local";

    /** {@code users.source} 取值：OIDC 外部账号（OneAccess / 华为账号） */
    public static final String SOURCE_OIDC = "oidc";

    /** 平台 JWT 的角色 claim：值为 {@code roles.role_key} 列表（无角色时为空数组，不省略） */
    public static final String CLAIM_ROLES = "roles";

    /** 平台 JWT 的来源 claim：{@link #SOURCE_LOCAL} / {@link #SOURCE_OIDC} */
    public static final String CLAIM_SOURCE = "source";

    private final UserId id;
    private final String username;
    private final String password;
    private final Short status;
    private final String source;
    private LocalDateTime lastLogin;

    private UserEntity(Builder builder) {
        this.id = builder.id;
        this.username = builder.username;
        this.password = builder.password;
        this.status = builder.status;
        this.source = builder.source == null || builder.source.isBlank() ? SOURCE_LOCAL : builder.source;
        this.lastLogin = builder.lastLogin;
    }

    /**
     * 是否有效（status == 1）
     */
    public boolean isActive() {
        return status != null && status == 1;
    }

    /**
     * 校验明文密码是否匹配 BCrypt 哈希
     *
     * @param rawPassword 明文密码
     * @return 是否匹配
     */
    public boolean passwordMatches(String rawPassword) {
        return io.quarkus.elytron.security.common.BcryptUtil.matches(rawPassword, this.password);
    }

    /**
     * 领域行为：生成访问令牌和刷新令牌（本地账号口径：无角色、source=local）
     *
     * @param signingKey 用于签署 JWT 的私钥
     * @return TokenDTO
     */
    public TokenDTO generateToken(PrivateKey signingKey) {
        return generateToken(signingKey, List.of(), SOURCE_LOCAL);
    }

    /**
     * 领域行为：生成访问令牌和刷新令牌（携带角色与账号来源）
     *
     * @param signingKey 用于签署 JWT 的私钥
     * @param roles      角色标识列表（{@code roles.role_key}），为 null 时按空列表签发
     * @param source     账号来源：{@link #SOURCE_LOCAL} / {@link #SOURCE_OIDC}
     * @return TokenDTO
     */
    public TokenDTO generateToken(PrivateKey signingKey, List<String> roles, String source) {
        TokenDTO dto = generateTokenWithKey(this.username, signingKey, roles, source);
        this.lastLogin = LocalDateTime.now();
        return dto;
    }

    /**
     * 静态工厂 — 不依赖实体实例签发令牌（用于刷新令牌场景）
     */
    public static TokenDTO generateTokenWithKey(String username, PrivateKey signingKey) {
        return generateTokenWithKey(username, signingKey, List.of(), SOURCE_LOCAL);
    }

    /**
     * 静态工厂 — 签发带角色与来源声明的令牌对。
     *
     * <p>claims 口径（同一用户 access / refresh 一致）：</p>
     * <ul>
     *   <li>{@code roles}：{@code roles.role_key} 列表，恒存在（无角色为空数组）；</li>
     *   <li>{@code source}：{@code local} / {@code oidc}，用于前端区分账号来源、后续按来源收紧策略。</li>
     * </ul>
     *
     * @param username   用户名
     * @param signingKey 私钥
     * @param roles      角色标识列表
     * @param source     账号来源
     * @return 平台令牌对
     */
    public static TokenDTO generateTokenWithKey(String username, PrivateKey signingKey,
                                                List<String> roles, String source) {
        logger.info("Generating tokens for user {}", username);
        List<String> roleClaims = roles == null ? List.of() : List.copyOf(roles);
        String sourceClaim = source == null || source.isBlank() ? SOURCE_LOCAL : source;

        // access token: 1小时有效期
        String accessToken = Jwt.claims()
                .subject(username)
                .issuer("hisilicon-knowledge-platform")
                .issuedAt(Instant.now())
                .expiresIn(java.time.Duration.ofHours(1))
                .claim("type", "access")
                .claim(CLAIM_ROLES, roleClaims)
                .claim(CLAIM_SOURCE, sourceClaim)
                .sign(signingKey);

        // refresh token: 2小时有效期
        String refreshToken = Jwt.claims()
                .subject(username)
                .issuer("hisilicon-knowledge-platform")
                .issuedAt(Instant.now())
                .expiresIn(java.time.Duration.ofHours(2))
                .claim("type", "refresh")
                .claim("access_token", accessToken)
                .claim(CLAIM_ROLES, roleClaims)
                .claim(CLAIM_SOURCE, sourceClaim)
                .sign(signingKey);

        return new TokenDTO(accessToken, refreshToken);
    }

    // ========== getters ==========

    public UserId getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public Short getStatus() { return status; }
    public String getSource() { return source; }
    public LocalDateTime getLastLogin() { return lastLogin; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UserId id;
        private String username;
        private String password;
        private Short status;
        private String source;
        private LocalDateTime lastLogin;

        public Builder id(UserId id) { this.id = id; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder lastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; return this; }

        public UserEntity build() {
            if (id == null) throw new IllegalStateException("用户 ID 不能为空");
            return new UserEntity(this);
        }
    }
}
