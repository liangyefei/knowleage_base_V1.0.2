package com.hisilicon.knowledge.platform.commons.web.security;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthenticationFilter} 用户状态有效性校验测试
 * （纯单元测试，Mockito mock Redis 与 {@link UserStatusChecker}）。
 * <p>覆盖：Redis 命中 status=1 放行 / status=2 拒绝 / 未命中且 checker 正常回填放行 /
 * 未命中且无 checker 放行+warn / checker 回查失败兜底放行。</p>
 * <p>说明：AuthenticationFilter#filter 全链路（含 LocaleUtil.getAppMessage 的既有 i18n 解析）
 * 依赖 ArC 容器 + Qute MessageBundles，纯单元测试环境不可用；过滤器对
 * {@link UserStatusValidator} 的委托即本用例覆盖的用户状态校验语义。</p>
 */
class AuthenticationFilterTest {

    private static final String USERNAME = "alice";
    private static final String CACHE_KEY = UserStatusValidator.CACHE_KEY_PREFIX + USERNAME;
    private static final String STATUS_DISABLED = "2";

    private Instance<RedisDataSource> redisInstance;
    private Instance<UserStatusChecker> checkerInstance;
    private RedisDataSource redisDataSource;
    @SuppressWarnings("unchecked")
    private final ValueCommands<String, String> valueCommands = mock(ValueCommands.class);
    private UserStatusChecker checker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisDataSource = mock(RedisDataSource.class);
        redisInstance = mock(Instance.class);
        checkerInstance = mock(Instance.class);
        checker = mock(UserStatusChecker.class);

        when(redisInstance.isUnsatisfied()).thenReturn(false);
        when(redisInstance.get()).thenReturn(redisDataSource);
        when(redisDataSource.value(String.class)).thenReturn(valueCommands);
        when(checkerInstance.isUnsatisfied()).thenReturn(false);
        when(checkerInstance.get()).thenReturn(checker);
    }

    @Test
    void redisHitActiveStatusAllowsRequest() {
        when(valueCommands.get(CACHE_KEY)).thenReturn(UserStatusValidator.STATUS_ACTIVE);

        assertDoesNotThrow(() -> validator().assertUserActive(USERNAME, messages()));
        verifyNoInteractions(checkerInstance);
        verify(valueCommands, never()).setex(CACHE_KEY, UserStatusValidator.TTL_SECONDS,
                UserStatusValidator.STATUS_ACTIVE);
    }

    @Test
    void redisHitDisabledStatusRejectsRequest() {
        when(valueCommands.get(CACHE_KEY)).thenReturn(STATUS_DISABLED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator().assertUserActive(USERNAME, messages()));
        assertEquals(ErrorCodeEnum.USER_ERROR_A0202, ex.getErrorCodeEnum());
    }

    @Test
    void cacheMissWithCheckerBackfillsAndAllows() {
        when(valueCommands.get(CACHE_KEY)).thenReturn(null);
        when(checker.statusOf(USERNAME)).thenReturn(1);

        assertDoesNotThrow(() -> validator().assertUserActive(USERNAME, messages()));
        verify(valueCommands).setex(CACHE_KEY, UserStatusValidator.TTL_SECONDS,
                UserStatusValidator.STATUS_ACTIVE);
    }

    @Test
    void cacheMissWithoutCheckerAllowsAndWarns() {
        when(valueCommands.get(CACHE_KEY)).thenReturn(null);
        when(checkerInstance.isUnsatisfied()).thenReturn(true);

        assertDoesNotThrow(() -> validator().assertUserActive(USERNAME, messages()));
        verify(checkerInstance, never()).get();
        verify(valueCommands, never()).setex(CACHE_KEY, UserStatusValidator.TTL_SECONDS,
                UserStatusValidator.STATUS_ACTIVE);
    }

    @Test
    void checkerFailureFallsBackToAllow() {
        when(valueCommands.get(CACHE_KEY)).thenReturn(null);
        when(checker.statusOf(USERNAME)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> validator().assertUserActive(USERNAME, messages()));
    }

    // ========== fixtures ==========

    private UserStatusValidator validator() {
        return new UserStatusValidator(redisInstance, checkerInstance);
    }

    private AppMessages messages() {
        // LocaleUtil 依赖 ArC 容器，纯单元测试直接 mock Qute 消息 bundle 接口
        AppMessages messages = mock(AppMessages.class);
        when(messages.loginAccountDisabled()).thenReturn("account disabled");
        return messages;
    }
}
