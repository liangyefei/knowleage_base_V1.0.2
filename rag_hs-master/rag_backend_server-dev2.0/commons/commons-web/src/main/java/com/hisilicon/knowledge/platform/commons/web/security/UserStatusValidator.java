package com.hisilicon.knowledge.platform.commons.web.security;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户状态缓存校验器（cache-aside）。
 * <p>
 * 供 {@link AuthenticationFilter} 在令牌基础校验通过后追加"用户当前状态有效性"校验：
 * 禁用 / 删除的用户即使持有效 token 也拒绝（与过滤器既有失败响应同构，见异常构造）。
 * </p>
 * <p>
 * 缓存策略：Redis key {@code user:status:{username}}，值为用户 status 字符串
 * （"1" = 正常），TTL 600 秒。命中直接判断；未命中经 {@link UserStatusChecker}
 * 回查并回填（实现方需在业务模块提供 UserStatusChecker bean，见其 javadoc）。
 * </p>
 * <p>
 * 失败语义（向后兼容）：Redis 不可用、无 {@link UserStatusChecker} 实现或回查失败时
 * 放行并记录 warn 日志，不阻断鉴权主流程 —— 边界兜底宽异常按项目规范注释保留。
 * Redis 数据源与 Checker 均经 {@link Instance} 可选注入，缺失时不影响启动。
 * </p>
 */
@ApplicationScoped
public class UserStatusValidator {

    private static final Logger logger = LoggerFactory.getLogger(UserStatusValidator.class);

    /** Redis 缓存 key 前缀：{@code user:status:{username}} */
    public static final String CACHE_KEY_PREFIX = "user:status:";

    /** 缓存过期时间（秒）：600 秒 */
    public static final long TTL_SECONDS = 600;

    /** 用户状态"正常"值（与 users 表 status 字段一致） */
    public static final String STATUS_ACTIVE = "1";

    private final Instance<RedisDataSource> redis;
    private final Instance<UserStatusChecker> checker;

    @Inject
    public UserStatusValidator(Instance<RedisDataSource> redis,
                               Instance<UserStatusChecker> checker) {
        this.redis = redis;
        this.checker = checker;
    }

    /**
     * 校验用户当前状态是否有效，无效（禁用 / 删除）则抛业务异常（401 语义）。
     *
     * @param username 用户名（JWT sub）
     * @param messages 按请求 locale 解析的 i18n 消息
     * @throws BusinessException 用户状态非"正常"时抛出
     */
    public void assertUserActive(String username, AppMessages messages) {
        String cached = cachedStatus(username);
        String status = cached != null ? cached : loadAndCacheStatus(username);

        if (status != null && !STATUS_ACTIVE.equals(status)) {
            logger.warn("Authentication failed: user {} is not active (status={})", username, status);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0202,
                    messages.loginAccountDisabled());
        }
    }

    // ========== 内部实现 ==========

    /** Redis 读取缓存，尽力而为：数据源缺失或读写异常时返回 null（视为未命中） */
    private String cachedStatus(String username) {
        if (redis.isUnsatisfied()) {
            return null;
        }
        try {
            return redis.get().value(String.class).get(CACHE_KEY_PREFIX + username);
        } catch (RuntimeException e) {
            // Redis 不可用兜底：尽力而为，不阻断鉴权主流程（项目规范获准场景）
            logger.warn("User status cache read failed, treating as cache miss: {}", e.getMessage());
            return null;
        }
    }

    /** 缓存未命中时经 UserStatusChecker 回查并回填；无实现或回查失败返回 null（放行） */
    private String loadAndCacheStatus(String username) {
        if (checker.isUnsatisfied()) {
            logger.warn("No UserStatusChecker bean found, skip user status validation for {}", username);
            return null;
        }
        try {
            String status = String.valueOf(checker.get().statusOf(username));
            cacheStatus(username, status);
            return status;
        } catch (RuntimeException e) {
            // 状态回查失败兜底：向后兼容（未接入状态校验的部署形态），不阻断鉴权主流程
            logger.warn("User status lookup failed, allowing request for {}: {}", username, e.getMessage());
            return null;
        }
    }

    /** 回填缓存，尽力而为：写失败仅记录 warn，等待下次 TTL 内回查 */
    private void cacheStatus(String username, String status) {
        try {
            redis.get().value(String.class).setex(CACHE_KEY_PREFIX + username, TTL_SECONDS, status);
        } catch (RuntimeException e) {
            logger.warn("User status cache write failed for {}: {}", username, e.getMessage());
        }
    }
}
