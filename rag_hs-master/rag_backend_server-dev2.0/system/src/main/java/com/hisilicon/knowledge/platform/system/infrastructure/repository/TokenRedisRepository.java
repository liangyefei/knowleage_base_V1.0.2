package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.RedisAPI;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.List;

/**
 * 令牌 Redis 仓储
 * <p>刷新令牌存储 + 用户当前令牌索引，用于登录互踢和刷新校验。</p>
 */
@ApplicationScoped
public class TokenRedisRepository {

    private static final String REFRESH_PREFIX = "refresh_token:";
    private static final String USER_TOKEN_PREFIX = "user_token:";
    private static final Duration REFRESH_TTL = Duration.ofHours(2);
    private static final Duration USER_TOKEN_TTL = Duration.ofHours(2);

    private final RedisAPI redis;

    public TokenRedisRepository(RedisAPI redis) {
        this.redis = redis;
    }

    // ======================== 刷新令牌存储 ========================

    /**
     * 保存刷新令牌的 JTI
     */
    public Uni<Void> saveRefreshToken(String jti, String username) {
        String key = REFRESH_PREFIX + jti;
        return redis.setex(key, String.valueOf(REFRESH_TTL.toSeconds()), username)
                .replaceWithVoid();
    }

    /**
     * 校验并删除刷新令牌（一次性消费）
     */
    public Uni<String> consumeRefreshToken(String jti) {
        String key = REFRESH_PREFIX + jti;
        return redis.get(key)
                .onItem().transformToUni(resp -> {
                    if (resp == null) {
                        return Uni.createFrom().nullItem();
                    }
                    String username = resp.toString();
                    return redis.del(List.of(key)).replaceWith(username);
                });
    }

    /**
     * 直接删除刷新令牌（不等校验，用于登录互踢）
     */
    public Uni<Void> deleteRefreshToken(String jti) {
        String key = REFRESH_PREFIX + jti;
        return redis.del(List.of(key)).replaceWithVoid();
    }

    /**
     * 校验刷新令牌是否属于指定用户，若一致则删除（一次性消费 + 防止篡改）
     *
     * @param jti      刷新令牌 JTI
     * @param username 期望的用户名
     * @return 删除成功返回 true，不存在或不匹配返回 false
     */
    public Uni<Boolean> verifyAndDeleteRefreshToken(String jti, String username) {
        String key = REFRESH_PREFIX + jti;
        return redis.get(key)
                .map(resp -> resp != null && username.equals(resp.toString()))
                .flatMap(matched -> {
                    if (matched) {
                        return redis.del(List.of(key)).map(r -> true);
                    }
                    return Uni.createFrom().item(false);
                });
    }

    // ======================== 用户当前令牌索引 ========================

    private static final String TOKEN_SEPARATOR = "|";

    /**
     * 记录用户当前有效的令牌索引（每次登录覆盖，旧令牌自动过期）
     *
     * @param username    用户名
     * @param refreshJti  刷新令牌 JTI
     * @param accessJti   访问令牌 JTI（用于退出登录时验证会话归属）
     */
    public Uni<Void> setUserCurrentToken(String username, String refreshJti, String accessJti) {
        String key = USER_TOKEN_PREFIX + username;
        String value = refreshJti + TOKEN_SEPARATOR + accessJti;
        return redis.setex(key, String.valueOf(USER_TOKEN_TTL.toSeconds()), value)
                .replaceWithVoid();
    }

    /**
     * 获取用户当前令牌的原始存储值（格式 {@code refreshJti|accessJti}），
     * 若不存在则返回空字符串
     */
    public Uni<String> getUserCurrentToken(String username) {
        String key = USER_TOKEN_PREFIX + username;
        return redis.get(key)
                .map(resp -> resp == null ? "" : resp.toString());
    }

    /**
     * 删除用户当前令牌索引（退出登录时使用）
     */
    public Uni<Void> deleteUserCurrentToken(String username) {
        String key = USER_TOKEN_PREFIX + username;
        return redis.del(List.of(key)).replaceWithVoid();
    }
}
