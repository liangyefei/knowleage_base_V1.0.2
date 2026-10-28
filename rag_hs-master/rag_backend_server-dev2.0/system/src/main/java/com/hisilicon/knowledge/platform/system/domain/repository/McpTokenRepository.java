package com.hisilicon.knowledge.platform.system.domain.repository;

import com.hisilicon.knowledge.platform.system.domain.entity.McpToken;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MCP 令牌仓储（{@code mcp_tokens}）。
 *
 * <p>查询一律按 jti 或 username：表按 {@code HASH("jti")} 分布，按 username 查询会跨节点，
 * 但本表规模是「用户数 × 每人几个令牌」，全扫描可接受，故未建二级索引
 * （migration_022 只允许主键/唯一键索引）。</p>
 *
 * @author hisilicon
 */
public interface McpTokenRepository {

    /**
     * 新增一条签发记录。
     *
     * @param token 领域实体
     * @return 完成信号
     */
    Uni<Void> save(McpToken token);

    /**
     * 按 jti 查询。
     *
     * @param jti JWT ID
     * @return 命中时返回实体
     */
    Uni<Optional<McpToken>> findByJti(String jti);

    /**
     * 按账号查询全部令牌（含已注销/已过期），按签发时间倒序。
     *
     * @param username 平台账号
     * @return 令牌列表，无记录时为空列表
     */
    Uni<List<McpToken>> findByUsername(String username);

    /**
     * 标记注销（只写 revoked_at，不删行）。
     *
     * @param jti          JWT ID
     * @param revokedTime  注销时间
     * @return 影响行数；0 表示 jti 不存在
     */
    Uni<Integer> markRevoked(String jti, LocalDateTime revokedTime);
}