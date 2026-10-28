package com.hisilicon.knowledge.platform.system.domain.repository;

import io.smallrye.mutiny.Uni;

/**
 * 外部身份关联仓储接口（{@code external_identities}）。
 *
 * <p>OIDC（OneAccess / 华为账号）登录与平台用户的绑定键是
 * {@code (source, external_id)}，而不是可变的邮箱 / 手机号 / 用户名：
 * 首次登录建号后写入绑定，后续登录按 {@code sub} 找回平台用户。</p>
 *
 * <p>本仓储只负责绑定关系本身；{@code users} 的写入在应用服务层完成，
 * {@code user_roles} 的写侧归 {@link UserRoleRepository}（角色管理）。</p>
 */
public interface ExternalIdentityRepository {

    /** OneAccess 在 {@code external_identities.source} / {@code idp_name} 中的固定取值 */
    String SOURCE_ONEACCESS = "oneaccess";

    /**
     * 外部身份投影（含关联用户的登录名，命中后调用方无需再查一次 users）。
     *
     * @param id         绑定行主键（{@code external_identities.id}）
     * @param userId     平台用户 ID（{@code users.id}）
     * @param username   平台登录名（{@code users.username}）；关联用户已不存在时为 {@code null}
     * @param source     来源，固定 {@link #SOURCE_ONEACCESS}
     * @param externalId 外部唯一标识（OIDC {@code sub}）
     * @param idpName    IdP 名称，固定 {@link #SOURCE_ONEACCESS}
     */
    record ExternalIdentity(String id, String userId, String username,
                            String source, String externalId, String idpName) {
    }

    /**
     * 按来源 + 外部唯一标识查询绑定。
     *
     * @param source     来源（{@code oneaccess}）
     * @param externalId 外部唯一标识（OIDC {@code sub}）
     * @return 绑定；不存在返回 {@code null} item
     */
    Uni<ExternalIdentity> findBySourceAndExternalId(String source, String externalId);

    /**
     * 按平台用户 ID 查询绑定（用于判定用户是否属于外部账号，刷新令牌时据此决定是否校验外部会话）。
     *
     * @param userId 平台用户 ID
     * @return 绑定；不存在返回 {@code null} item
     */
    Uni<ExternalIdentity> findByUserId(String userId);

    /**
     * 新增一条外部身份绑定（由调用方负责事务，并与 users 插入处于同一事务）。
     *
     * @param identity 待写入绑定（{@code id} 由调用方生成）
     * @return 完成
     */
    Uni<Void> create(ExternalIdentity identity);
}
