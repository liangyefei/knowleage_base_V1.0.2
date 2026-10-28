package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.RedisAPI;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

/**
 * OIDC 短生命周期状态 Redis 仓储。
 *
 * <p>存放两类数据，都是"外部账号登录链路"的临时状态：</p>
 * <ol>
 *   <li><b>外部访问令牌</b>：键 {@code oidc:at:{username}}，值 = OneAccess access token。
 *       刷新平台令牌时用它做外部会话校验（{@code ExternalAccountValidator}），取不到即拒绝刷新；</li>
 *   <li><b>一次性授权码</b>：键 {@code oidc:code:{code}}，值 = {@code refreshToken|accessToken}，
 *       固定 TTL 60 秒，取出即删。用于 OIDC 回调（服务端）到前端中转页之间的平台令牌交接，
 *       避免把平台 refresh token 放进浏览器可见的 URL。</li>
 * </ol>
 *
 * <p>Redis 客户端用法与 {@link TokenRedisRepository} 保持一致（构造注入 {@link RedisAPI}、{@code setex}）。</p>
 */
@ApplicationScoped
public class ExternalTokenRedisRepository {

    private static final Logger logger = LoggerFactory.getLogger(ExternalTokenRedisRepository.class);

    /** 外部访问令牌缓存键前缀：{@code oidc:at:{username}} */
    public static final String ACCESS_TOKEN_KEY_PREFIX = "oidc:at:";

    /** 一次性授权码键前缀：{@code oidc:code:{code}} */
    public static final String CODE_KEY_PREFIX = "oidc:code:";

    /** 一次性授权码 TTL：60 秒（交接窗口极短，过期即视为失败） */
    public static final Duration CODE_TTL = Duration.ofSeconds(60);

    /** 外部访问令牌默认 TTL：2 小时（令牌不是 JWT / 无 exp 时兜底） */
    public static final Duration DEFAULT_ACCESS_TOKEN_TTL = Duration.ofHours(2);

    /** 外部访问令牌最短 TTL：避免"签发即过期"的边界条件 */
    private static final Duration MIN_ACCESS_TOKEN_TTL = Duration.ofSeconds(60);

    /** 一次性授权码值分隔符：refreshToken|accessToken（JWT 仅含 base64url 与 '.'，不会包含 '|'） */
    private static final String CODE_VALUE_SEPARATOR = "|";

    private final RedisAPI redis;

    public ExternalTokenRedisRepository(RedisAPI redis) {
        this.redis = redis;
    }

    // ======================== 外部访问令牌 ========================

    /**
     * 保存 OneAccess access token（TTL 由令牌自身 {@code exp} 推导，解析不到时用 2 小时）。
     *
     * @param username    平台用户名
     * @param accessToken 外部 access token
     * @return 完成
     */
    public Uni<Void> saveAccessToken(String username, String accessToken) {
        String key = ACCESS_TOKEN_KEY_PREFIX + username;
        long ttlSeconds = resolveAccessTokenTtlSeconds(accessToken);
        return redis.setex(key, String.valueOf(ttlSeconds), accessToken).replaceWithVoid();
    }

    /**
     * 读取外部 access token。
     *
     * @param username 平台用户名
     * @return 令牌；不存在返回空字符串（与 {@link TokenRedisRepository#getUserCurrentToken(String)} 口径一致）
     */
    public Uni<String> getAccessToken(String username) {
        String key = ACCESS_TOKEN_KEY_PREFIX + username;
        return redis.get(key).map(resp -> resp == null ? "" : resp.toString());
    }

    /**
     * 删除外部 access token（退出登录时清理）。
     *
     * @param username 平台用户名
     * @return 完成
     */
    public Uni<Void> deleteAccessToken(String username) {
        return redis.del(List.of(ACCESS_TOKEN_KEY_PREFIX + username)).replaceWithVoid();
    }

    /**
     * 推导外部访问令牌的缓存 TTL。
     *
     * <p>quarkus-oidc 的 {@code SecurityIdentity} 不暴露令牌响应里的 {@code expires_in}，
     * 因此这里按 access token 自身的 JWT {@code exp} 计算剩余有效期（不做验签，仅用于缓存 TTL）；
     * OneAccess 返回不透明令牌时退化为 {@link #DEFAULT_ACCESS_TOKEN_TTL}。</p>
     */
    private long resolveAccessTokenTtlSeconds(String accessToken) {
        OptionalLong expiry = JwtUtils.extractExpiry(accessToken);
        if (expiry.isEmpty()) {
            return DEFAULT_ACCESS_TOKEN_TTL.toSeconds();
        }
        long remaining = expiry.getAsLong() - System.currentTimeMillis() / 1000;
        if (remaining < MIN_ACCESS_TOKEN_TTL.toSeconds()) {
            logger.warn("OneAccess access token is about to expire or already expired, ttl={}s", remaining);
            return MIN_ACCESS_TOKEN_TTL.toSeconds();
        }
        return remaining;
    }

    // ======================== 一次性授权码 ========================

    /**
     * 保存一次性授权码（TTL 60 秒）。
     *
     * @param code         一次性授权码（UUID，调用方生成）
     * @param refreshToken 平台 refresh token
     * @param accessToken  平台 access token
     * @return 完成
     */
    public Uni<Void> saveCode(String code, String refreshToken, String accessToken) {
        String value = refreshToken + CODE_VALUE_SEPARATOR + accessToken;
        return redis.setex(CODE_KEY_PREFIX + code, String.valueOf(CODE_TTL.toSeconds()), value)
                .replaceWithVoid();
    }

    /**
     * 消费一次性授权码：读取后立即删除（一次性）。
     *
     * @param code 一次性授权码
     * @return 令牌对；不存在或已过期返回 {@code null}
     */
    public Uni<CodePayload> consumeCode(String code) {
        String key = CODE_KEY_PREFIX + code;
        return redis.get(key)
                .onItem().transformToUni(resp -> {
                    if (resp == null) {
                        return Uni.createFrom().nullItem();
                    }
                    String value = resp.toString();
                    // 取出即删：即使后续解析失败也不再可用（防重放）
                    return redis.del(List.of(key)).map(deleted -> parseCodePayload(value));
                });
    }

    private CodePayload parseCodePayload(String value) {
        int separator = value == null ? -1 : value.indexOf(CODE_VALUE_SEPARATOR);
        if (separator <= 0 || separator >= value.length() - 1) {
            logger.warn("Malformed one-time OIDC code payload, treating as invalid");
            return null;
        }
        return new CodePayload(
                value.substring(0, separator),
                value.substring(separator + 1));
    }

    /**
     * 一次性授权码对应的平台令牌对。
     *
     * @param refreshToken 平台 refresh token
     * @param accessToken  平台 access token
     */
    public record CodePayload(String refreshToken, String accessToken) {
    }
}
