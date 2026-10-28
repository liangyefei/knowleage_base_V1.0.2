package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * MCP 网关入站令牌台账。
 *
 * <p>对应表 {@code mcp_tokens}（docs/sql/migration_025_mcp_tokens.sql）。一行一次签发，
 * 主键是 JWT 的 jti（DWS 分布式表唯一索引必须含分布列，故本表不设冗余 id 列）。
 *
 * <p>注销状态以 Redis {@code mcp:revoked:{jti}} 为准（网关读 Redis 即时生效），
 * 本表的 {@code revoked_at} 只作审计留痕；令牌明文不落库，只留 SHA-256 摘要。
 *
 * @author hisilicon
 */
@Entity
@Table(name = "mcp_tokens")
public class McpTokenPO {

    /** JWT ID，主键兼分布列 */
    @Id
    private String jti;

    /** 令牌归属平台账号（JWT sub） */
    private String username;

    /** 令牌明文的 SHA-256 十六进制摘要，不存明文 */
    private String tokenHash;

    /** 用户自填备注，便于自助识别与吊销 */
    private String label;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    /** 注销时间；NULL=未注销 */
    private LocalDateTime revokedAt;

    /** 预留列：网关不回写，恒为 NULL */
    private LocalDateTime lastUsedAt;

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}