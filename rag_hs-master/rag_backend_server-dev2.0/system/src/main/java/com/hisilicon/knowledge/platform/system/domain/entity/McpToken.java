package com.hisilicon.knowledge.platform.system.domain.entity;

import java.time.LocalDateTime;

/**
 * 领域层 MCP 令牌实体。
 *
 * <p>一次签发一行，主键是 JWT 的 jti。令牌明文不进领域、不落库，只有签发方法返回一次；
 * 实体里保留 SHA-256 摘要用于排障比对。</p>
 *
 * <p>可用性判定：未注销且未过期。Redis 里的吊销位（{@code mcp:revoked:{jti}}）是网关的
 * 即时判据，本实体的 {@code revokedAt} 是库内留痕，两者写入时机一致。</p>
 *
 * @author hisilicon
 */
public class McpToken {

    /** MCP 专用令牌的 JWT {@code type} claim 取值（与普通 access/refresh 区分，网关按此校验） */
    public static final String TYPE_MCP = "mcp";

    private final String jti;
    private final String username;
    private final String tokenHash;
    private final String label;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;
    private LocalDateTime revokedAt;

    private McpToken(Builder builder) {
        this.jti = builder.jti;
        this.username = builder.username;
        this.tokenHash = builder.tokenHash;
        this.label = builder.label;
        this.createdAt = builder.createdAt == null ? LocalDateTime.now() : builder.createdAt;
        this.expiresAt = builder.expiresAt;
        this.revokedAt = builder.revokedAt;
    }

    /**
     * 令牌在给定时刻是否可用（未注销且未过期）。
     *
     * @param now 判定基准时刻
     * @return 可用返回 {@code true}
     */
    public boolean isUsableAt(LocalDateTime now) {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt != null && expiresAt.isAfter(now);
    }

    /**
     * 标记注销。幂等：已注销时保留首次注销时间。
     *
     * @param revokedTime 注销时间
     */
    public void revoke(LocalDateTime revokedTime) {
        if (this.revokedAt == null) {
            this.revokedAt = revokedTime;
        }
    }

    public String getJti() { return jti; }
    public String getUsername() { return username; }
    public String getTokenHash() { return tokenHash; }
    public String getLabel() { return label; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String jti;
        private String username;
        private String tokenHash;
        private String label;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private LocalDateTime revokedAt;

        public Builder jti(String jti) { this.jti = jti; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder tokenHash(String tokenHash) { this.tokenHash = tokenHash; return this; }
        public Builder label(String label) { this.label = label; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder expiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder revokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; return this; }

        public McpToken build() {
            if (jti == null || jti.isBlank()) {
                throw new IllegalStateException("jti 不能为空");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalStateException("username 不能为空");
            }
            return new McpToken(this);
        }
    }
}