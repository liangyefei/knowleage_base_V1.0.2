package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.oidc.ExternalAccount;
import com.hisilicon.knowledge.platform.commons.oidc.ExternalAccountValidator;
import com.hisilicon.knowledge.platform.commons.oidc.OneAccessAccountValidator;
import com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils;
import com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils;
import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.UserId;
import com.hisilicon.knowledge.platform.system.domain.repository.ExternalIdentityRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import com.hisilicon.knowledge.platform.system.facade.MockTokenRedisRepository;
import com.hisilicon.knowledge.platform.system.facade.MockUserRepository;
import com.hisilicon.knowledge.platform.system.facade.MockUserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.ExternalIdentityRepositoryImpl;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.ExternalTokenRedisRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.TokenRedisRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code UserCommandService#refresh} 的外部账号校验接线测试。
 *
 * <p>用真实的 {@link UserCommandService} + {@link OidcUserCommandService}（仓储 / Redis / 外部校验全部 mock），
 * 验证刷新链路三个分支：无外部令牌拒绝 / 校验通过刷新成功 / 校验异常透传。</p>
 *
 * <p>必须走 {@code @QuarkusTest}：刷新入口要用 Qute 消息包（{@code LocaleUtil}），纯单元测试无法初始化 ArC。
 * 刷新令牌由 {@link UserEntity#generateTokenWithKey} 真实签发，因此断言的是真实 claim。</p>
 */
@QuarkusTest
class UserCommandServiceRefreshExternalTest {

    private static final String USERNAME = "oa_1a2b3c4d";
    private static final String USER_ID = "u-1";
    private static final String EXTERNAL_ACCESS_TOKEN = "oneaccess-access-token";
    private static final String PROVIDER = "huawei";
    private static final String LOCALE = "zh-CN";

    @Inject
    UserCommandService userCommandService;

    @Inject
    UserRepository userRepository;

    @Inject
    TokenRedisRepository tokenRedisRepository;

    @Inject
    UserRoleQueryRepository userRoleQueryRepository;

    @Inject
    ExternalIdentityRepository externalIdentityRepository;

    @Inject
    ExternalTokenRedisRepository externalTokenRedisRepository;

    private ExternalIdentityRepository externalIdentityMock;
    private ExternalTokenRedisRepository externalTokenMock;
    private OneAccessAccountValidator externalAccountValidatorMock;
    private MockTokenRedisRepository tokenRedisMock;
    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockUserRepository(), userRepository);
        tokenRedisMock = new MockTokenRedisRepository();
        QuarkusMock.installMockForInstance(tokenRedisMock, tokenRedisRepository);
        QuarkusMock.installMockForInstance(new MockUserRoleQueryRepository(), userRoleQueryRepository);

        externalIdentityMock = mock(ExternalIdentityRepositoryImpl.class);
        QuarkusMock.installMockForInstance(externalIdentityMock, externalIdentityRepository);
        externalTokenMock = mock(ExternalTokenRedisRepository.class);
        QuarkusMock.installMockForInstance(externalTokenMock, externalTokenRedisRepository);

        // Provider 校验实现：按实现类替换（QuarkusMock.installMockForType 要求 mock 可赋值给 bean 类型，
        // 所以 mock 具体实现类 OneAccessAccountValidator 而不是接口）。
        externalAccountValidatorMock = mock(OneAccessAccountValidator.class);
        QuarkusMock.installMockForType(externalAccountValidatorMock, OneAccessAccountValidator.class);

        when(externalAccountValidatorMock.supports(PROVIDER)).thenReturn(true);
        // 外部账号：绑定存在
        when(externalIdentityMock.findByUserId(USER_ID))
                .thenReturn(Uni.createFrom().item(boundIdentity()));
        // 用户启用、角色为内置 community
        MockUserRepository.setReturnUser(activeUser());
        MockUserRoleQueryRepository.reset();
        MockTokenRedisRepository.setVerified(true);
        MockTokenRedisRepository.resetSaveRefreshCount();
    }

    // ======================== 三个分支 ========================

    @Test
    @DisplayName("外部账号但 OneAccess 令牌缺失：拒绝刷新（A0224），不签发新令牌")
    void shouldRejectRefreshWithoutExternalToken() {
        when(externalTokenMock.getAccessToken(USERNAME)).thenReturn(Uni.createFrom().item(""));
        TokenPair pair = issueTokenPair();

        BusinessException error = assertThrows(BusinessException.class,
                () -> userCommandService.refresh(pair.accessToken(), pair.refreshToken(), LOCALE)
                        .await().indefinitely());

        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, error.getErrorCodeEnum());
        assertEquals("外部账号会话已过期，请重新登录", error.getMessage());
        verify(externalAccountValidatorMock, never()).validate(anyString(), anyString());
        assertEquals(0, MockTokenRedisRepository.saveRefreshCount());
    }

    @Test
    @DisplayName("外部账号且校验通过：刷新成功，新令牌保留 roles 与 source=oidc")
    void shouldRefreshAfterExternalValidation() {
        when(externalTokenMock.getAccessToken(USERNAME))
                .thenReturn(Uni.createFrom().item(EXTERNAL_ACCESS_TOKEN));
        when(externalAccountValidatorMock.validate(PROVIDER, EXTERNAL_ACCESS_TOKEN))
                .thenReturn(new ExternalAccount("sub-123", "zhangsan", "张三",
                        "zhangsan@example.com", "13800000000", null, Map.of()));
        TokenPair pair = issueTokenPair();

        TokenDTO refreshed = userCommandService.refresh(pair.accessToken(), pair.refreshToken(), LOCALE)
                .await().indefinitely();

        String payload = JwtUtils.parsePayload(refreshed.refreshToken())
                .orElseThrow(() -> new IllegalStateException("invalid jwt"));
        assertTrue(payload.contains("\"roles\":[\"community\"]"), payload);
        assertTrue(payload.contains("\"source\":\"oidc\""), payload);
        assertTrue(payload.contains("\"sub\":\"" + USERNAME + "\""), payload);

        verify(externalAccountValidatorMock).validate(PROVIDER, EXTERNAL_ACCESS_TOKEN);
        assertEquals(1, MockTokenRedisRepository.saveRefreshCount());
    }

    @Test
    @DisplayName("校验实现抛 BusinessException：原样透传，不签发新令牌")
    void shouldPropagateValidatorFailure() {
        when(externalTokenMock.getAccessToken(USERNAME))
                .thenReturn(Uni.createFrom().item(EXTERNAL_ACCESS_TOKEN));
        when(externalAccountValidatorMock.validate(PROVIDER, EXTERNAL_ACCESS_TOKEN)).thenThrow(
                new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, "外部账号会话已过期，请重新登录"));
        TokenPair pair = issueTokenPair();

        BusinessException error = assertThrows(BusinessException.class,
                () -> userCommandService.refresh(pair.accessToken(), pair.refreshToken(), LOCALE)
                        .await().indefinitely());

        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, error.getErrorCodeEnum());
        assertEquals("外部账号会话已过期，请重新登录", error.getMessage());
        assertEquals(0, MockTokenRedisRepository.saveRefreshCount());
    }

    // ======================== 辅助方法 ========================

    /**
     * 签发一对真实令牌，并把令牌仓库的 user_token 索引指向它（否则刷新在第 4 步就被拒）。
     */
    private TokenPair issueTokenPair() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                USERNAME, PemRsaUtils.getPrivateKey(), List.of("community"), UserEntity.SOURCE_OIDC);
        String refreshJti = JwtUtils.extractClaim(token.refreshToken(), "jti")
                .orElseThrow(() -> new IllegalStateException("refresh token must carry jti"));
        String accessJti = JwtUtils.extractClaim(token.accessToken(), "jti").orElse("");
        MockTokenRedisRepository.setCurrentUserToken(refreshJti + "|" + accessJti);
        return new TokenPair(token.accessToken(), token.refreshToken());
    }

    private static ExternalIdentityRepository.ExternalIdentity boundIdentity() {
        return new ExternalIdentityRepository.ExternalIdentity(
                "ext-1", USER_ID, USERNAME, "oneaccess", "sub-123", "oneaccess");
    }

    private static UserEntity activeUser() {
        return UserEntity.builder()
                .id(UserId.of(USER_ID))
                .username(USERNAME)
                .password(BcryptUtil.bcryptHash("placeholder"))
                .status((short) 1)
                .source(UserEntity.SOURCE_OIDC)
                .build();
    }

    /**
     * 平台令牌对。
     */
    private record TokenPair(String accessToken, String refreshToken) {
    }
}
