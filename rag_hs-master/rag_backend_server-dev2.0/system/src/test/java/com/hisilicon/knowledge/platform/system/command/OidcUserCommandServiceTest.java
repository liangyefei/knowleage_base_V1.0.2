package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.oidc.ExternalAccount;
import com.hisilicon.knowledge.platform.commons.oidc.ExternalAccountValidator;
import com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.UserId;
import com.hisilicon.knowledge.platform.system.domain.repository.ExternalIdentityRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.RoleRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleRepository;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import com.hisilicon.knowledge.platform.system.exception.UserDisabledException;
import com.hisilicon.knowledge.platform.system.infrastructure.po.UserPO;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.ExternalTokenRedisRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.TokenRedisRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.UserPanacheRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OidcUserCommandService} 单元测试（纯 Mockito，不启动 Quarkus）。
 *
 * <p>覆盖：已有绑定登录 / 首次登录建号 + 绑定 community / 禁用用户拒绝 /
 * 刷新时外部会话校验（本地账号、无令牌、校验通过、BusinessException 透传、无校验实现）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OidcUserCommandServiceTest {

    private static final String SUB = "oneaccess-sub-123";
    private static final String USERNAME = "oa_1a2b3c4d";
    private static final String USER_ID = "u-1";
    private static final String EXTERNAL_ACCESS_TOKEN = "oneaccess-access-token";
    private static final String PROVIDER = "huawei";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserPanacheRepository userPanacheRepository;

    @Mock
    private ExternalIdentityRepository externalIdentityRepository;

    @Mock
    private UserRoleQueryRepository userRoleQueryRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private TokenRedisRepository tokenRedisRepository;

    @Mock
    private ExternalTokenRedisRepository externalTokenRedisRepository;

    @Mock
    private ExternalAccountValidator accountValidator;

    @Mock
    private Instance<ExternalAccountValidator> accountValidators;

    private OidcUserCommandService service;
    private RoleEntity communityRole;

    @BeforeEach
    void setUp() {
        communityRole = RoleEntity.create(RoleEntity.COMMUNITY_ROLE_KEY, "社区用户", "");
        when(accountValidators.iterator()).thenReturn(List.of(accountValidator).iterator());
        when(accountValidator.supports(PROVIDER)).thenReturn(true);

        // 令牌落库的公共桩（各分支都要走）
        when(tokenRedisRepository.getUserCurrentToken(anyString())).thenReturn(Uni.createFrom().item(""));
        when(tokenRedisRepository.deleteRefreshToken(anyString())).thenReturn(Uni.createFrom().voidItem());
        when(tokenRedisRepository.saveRefreshToken(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());
        when(tokenRedisRepository.setUserCurrentToken(anyString(), anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());
        when(userRepository.updateLoginTime(anyString(), any(LocalDateTime.class)))
                .thenReturn(Uni.createFrom().item(1));
        when(externalTokenRedisRepository.saveAccessToken(anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        service = new OidcUserCommandService(
                userRepository,
                userPanacheRepository,
                externalIdentityRepository,
                userRoleQueryRepository,
                roleRepository,
                userRoleRepository,
                tokenRedisRepository,
                externalTokenRedisRepository,
                accountValidators,
                PROVIDER);
    }

    // ======================== 登录：已有绑定 ========================

    @Test
    @DisplayName("已有绑定且用户正常：签发带 roles/source=oidc 的平台令牌，不新建用户")
    void shouldLoginExistingBoundUser() {
        when(externalIdentityRepository.findBySourceAndExternalId("oneaccess", SUB))
                .thenReturn(Uni.createFrom().item(binding(USERNAME)));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Uni.createFrom().item(activeUser(USERNAME)));
        when(userRoleQueryRepository.findRoleKeysByUsername(USERNAME))
                .thenReturn(Uni.createFrom().item(List.of("community")));

        TokenDTO token = service.loginWithIdentity(PROVIDER, identity(true)).await().indefinitely();

        assertNotNull(token);
        String refreshPayload = payload(token.refreshToken());
        assertTrue(refreshPayload.contains("\"source\":\"oidc\""), refreshPayload);
        assertTrue(refreshPayload.contains("\"roles\":[\"community\"]"), refreshPayload);
        assertTrue(payload(token.accessToken()).contains("\"source\":\"oidc\""));

        // 外部 access token 落 Redis，供刷新时复核
        verify(externalTokenRedisRepository).saveAccessToken(USERNAME, EXTERNAL_ACCESS_TOKEN);
        // 命中绑定不建号
        verifyNoInteractions(userPanacheRepository);
        verify(userRepository).updateLoginTime(eq(USERNAME), any(LocalDateTime.class));
    }

    // ======================== 登录：首次建号 ========================

    @Test
    @DisplayName("首次登录：oa_ 前缀用户名 + source=oidc + 随机 BCrypt 占位口令 + 绑定 community")
    void shouldRegisterNewUserOnFirstLogin() {
        when(externalIdentityRepository.findBySourceAndExternalId("oneaccess", SUB))
                .thenReturn(Uni.createFrom().nullItem());
        when(userRepository.findByUsername(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(userPanacheRepository.persist(any(UserPO.class)))
                .thenAnswer(invocation -> Uni.createFrom().item((UserPO) invocation.getArgument(0)));
        when(roleRepository.findByRoleKey(RoleEntity.COMMUNITY_ROLE_KEY))
                .thenReturn(Uni.createFrom().item(Optional.of(communityRole)));
        when(userRoleRepository.replaceUserRoles(anyString(), anyCollection()))
                .thenReturn(Uni.createFrom().voidItem());
        when(userRoleQueryRepository.findRoleKeysByUsername(anyString()))
                .thenReturn(Uni.createFrom().item(List.of(RoleEntity.COMMUNITY_ROLE_KEY)));

        TokenDTO token = service.loginWithIdentity(PROVIDER, identity(true)).await().indefinitely();

        ArgumentCaptor<UserPO> userCaptor = ArgumentCaptor.forClass(UserPO.class);
        verify(userPanacheRepository).persist(userCaptor.capture());
        UserPO created = userCaptor.getValue();
        assertTrue(created.getUsername().startsWith("oa_"), created.getUsername());
        assertEquals(11, created.getUsername().length(), created.getUsername());
        assertEquals(UserEntity.SOURCE_OIDC, created.getSource());
        assertEquals((short) 1, created.getStatus().shortValue());
        assertEquals("张三", created.getRealName());
        assertEquals("zhangsan@example.com", created.getEmail());
        assertEquals("13800000000", created.getPhone());
        // 占位口令：不可用于口令登录
        assertFalse(BcryptUtil.matches("anything", created.getPassword()));
        assertNotNull(created.getCreatedAt());

        ArgumentCaptor<ExternalIdentityRepository.ExternalIdentity> bindingCaptor =
                ArgumentCaptor.forClass(ExternalIdentityRepository.ExternalIdentity.class);
        verify(externalIdentityRepository).create(bindingCaptor.capture());
        ExternalIdentityRepository.ExternalIdentity written = bindingCaptor.getValue();
        assertEquals(created.getId(), written.userId());
        assertEquals(SUB, written.externalId());
        assertEquals("oneaccess", written.source());
        assertEquals("oneaccess", written.idpName());

        verify(userRoleRepository).replaceUserRoles(
                eq(created.getId()), eq(List.of(communityRole.getId().value())));
        assertTrue(payload(token.refreshToken()).contains("\"roles\":[\"community\"]"));
        verify(externalTokenRedisRepository).saveAccessToken(created.getUsername(), EXTERNAL_ACCESS_TOKEN);
    }

    @Test
    @DisplayName("首次登录：community 角色不存在时仍建号成功（只记 error 日志）")
    void shouldRegisterNewUserEvenWhenCommunityRoleMissing() {
        when(externalIdentityRepository.findBySourceAndExternalId("oneaccess", SUB))
                .thenReturn(Uni.createFrom().nullItem());
        when(userRepository.findByUsername(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(userPanacheRepository.persist(any(UserPO.class)))
                .thenAnswer(invocation -> Uni.createFrom().item((UserPO) invocation.getArgument(0)));
        when(roleRepository.findByRoleKey(RoleEntity.COMMUNITY_ROLE_KEY))
                .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(userRoleQueryRepository.findRoleKeysByUsername(anyString()))
                .thenReturn(Uni.createFrom().item(List.of()));

        TokenDTO token = service.loginWithIdentity(PROVIDER, identity(true)).await().indefinitely();

        assertNotNull(token);
        assertTrue(payload(token.refreshToken()).contains("\"roles\":[]"));
        verify(userRoleRepository, never()).replaceUserRoles(anyString(), anyCollection());
    }

    // ======================== 登录：拒绝场景 ========================

    @Test
    @DisplayName("绑定用户被禁用：抛 UserDisabledException，不签发令牌")
    void shouldRejectDisabledUser() {
        when(externalIdentityRepository.findBySourceAndExternalId("oneaccess", SUB))
                .thenReturn(Uni.createFrom().item(binding(USERNAME)));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Uni.createFrom().item(disabledUser(USERNAME)));

        assertThrows(UserDisabledException.class,
                () -> service.loginWithIdentity(PROVIDER, identity(true)).await().indefinitely());
        verify(tokenRedisRepository, never()).saveRefreshToken(anyString(), anyString());
    }

    @Test
    @DisplayName("身份缺少 sub：fail-closed，抛 A0224")
    void shouldRejectIdentityWithoutSubject() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.loginWithIdentity(PROVIDER, identity(false)).await().indefinitely());

        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, error.getErrorCodeEnum());
        verifyNoInteractions(externalIdentityRepository);
    }

    // ======================== 刷新：上下文加载 ========================

    @Test
    @DisplayName("刷新上下文：外部账号返回 oidc + 角色列表")
    void shouldLoadRefreshContextForExternalUser() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Uni.createFrom().item(externalUser(USERNAME)));
        when(userRoleQueryRepository.findRoleKeysByUsername(USERNAME))
                .thenReturn(Uni.createFrom().item(List.of("community", "kb_editor")));

        OidcUserCommandService.RefreshContext ctx =
                service.loadRefreshContext(USERNAME).await().indefinitely();

        assertEquals(UserEntity.SOURCE_OIDC, ctx.source());
        assertEquals(List.of("community", "kb_editor"), ctx.roles());
    }

    @Test
    @DisplayName("刷新上下文：禁用用户直接拒绝（A0202 语义的 UserDisabledException）")
    void shouldRejectRefreshContextForDisabledUser() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Uni.createFrom().item(disabledUser(USERNAME)));

        assertThrows(UserDisabledException.class,
                () -> service.loadRefreshContext(USERNAME).await().indefinitely());
    }

    // ======================== 刷新：外部会话校验 ========================

    @Test
    @DisplayName("本地账号（无外部绑定）：刷新来源为 local，不访问外部令牌")
    void shouldResolveLocalSourceForLocalUser() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Uni.createFrom().item(activeUser(USERNAME)));
        when(externalIdentityRepository.findByUserId(USER_ID)).thenReturn(Uni.createFrom().nullItem());

        String source = service.resolveRefreshSource(USERNAME).await().indefinitely();

        assertEquals(UserEntity.SOURCE_LOCAL, source);
        verify(externalTokenRedisRepository, never()).getAccessToken(anyString());
    }

    @Test
    @DisplayName("外部账号 + 外部令牌缺失：requireValidExternalSession 拒绝（A0224）")
    void shouldRejectRefreshWhenExternalTokenMissing() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Uni.createFrom().item(externalUser(USERNAME)));
        when(externalTokenRedisRepository.getAccessToken(USERNAME)).thenReturn(Uni.createFrom().item(""));

        String source = service.resolveRefreshSource(USERNAME).await().indefinitely();
        assertEquals(UserEntity.SOURCE_OIDC, source);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requireValidExternalSession(USERNAME).await().indefinitely());
        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, error.getErrorCodeEnum());
        assertEquals("外部账号会话已过期，请重新登录", error.getMessage());
        verify(accountValidator, never()).validate(anyString(), anyString());
    }

    @Test
    @DisplayName("外部账号 + 校验通过：requireValidExternalSession 放行")
    void shouldRequireValidExternalSessionPass() {
        when(externalTokenRedisRepository.getAccessToken(USERNAME))
                .thenReturn(Uni.createFrom().item(EXTERNAL_ACCESS_TOKEN));
        when(accountValidator.validate(PROVIDER, EXTERNAL_ACCESS_TOKEN)).thenReturn(externalAccount());

        service.requireValidExternalSession(USERNAME).await().indefinitely();

        verify(accountValidator).validate(PROVIDER, EXTERNAL_ACCESS_TOKEN);
    }

    @Test
    @DisplayName("外部账号 + 校验抛 BusinessException：requireValidExternalSession 原样透传（fail-closed）")
    void shouldPropagateValidatorBusinessException() {
        when(externalTokenRedisRepository.getAccessToken(USERNAME))
                .thenReturn(Uni.createFrom().item(EXTERNAL_ACCESS_TOKEN));
        when(accountValidator.validate(PROVIDER, EXTERNAL_ACCESS_TOKEN)).thenThrow(
                new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, "外部账号会话已过期，请重新登录"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requireValidExternalSession(USERNAME).await().indefinitely());

        assertEquals(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, error.getErrorCodeEnum());
        assertEquals("外部账号会话已过期，请重新登录", error.getMessage());
    }

    @Test
    @DisplayName("没有校验实现支持该 provider：requireValidExternalSession fail-closed（C0114）")
    void shouldFailClosedWhenNoValidatorSupportsProvider() {
        when(externalTokenRedisRepository.getAccessToken(USERNAME))
                .thenReturn(Uni.createFrom().item(EXTERNAL_ACCESS_TOKEN));
        when(accountValidator.supports(PROVIDER)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requireValidExternalSession(USERNAME).await().indefinitely());

        assertEquals(ErrorCodeEnum.SERVICE_ERROR_C0114, error.getErrorCodeEnum());
    }

    // ======================== 辅助方法 ========================

    private static SecurityIdentity identity(boolean withSubject) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        JsonWebToken idToken = mock(JsonWebToken.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(idToken);
        when(identity.getCredential(AccessTokenCredential.class))
                .thenReturn(new AccessTokenCredential(EXTERNAL_ACCESS_TOKEN));
        when(idToken.getClaim("sub")).thenReturn(withSubject ? SUB : null);
        when(idToken.getClaim("preferred_username")).thenReturn("zhangsan");
        when(idToken.getClaim("name")).thenReturn("张三");
        when(idToken.getClaim("email")).thenReturn("zhangsan@example.com");
        when(idToken.getClaim("phone")).thenReturn("13800000000");
        return identity;
    }

    private static ExternalIdentityRepository.ExternalIdentity binding(String username) {
        return new ExternalIdentityRepository.ExternalIdentity(
                "ext-1", USER_ID, username, "oneaccess", SUB, "oneaccess");
    }

    private static UserEntity activeUser(String username) {
        return UserEntity.builder()
                .id(UserId.of(USER_ID))
                .username(username)
                .password(BcryptUtil.bcryptHash("placeholder"))
                .status((short) 1)
                .build();
    }

    /**
     * 外部账号（{@code users.source='oidc'}）：刷新链路据 source 判断是否走 OneAccess 校验。
     */
    private static UserEntity externalUser(String username) {
        return UserEntity.builder()
                .id(UserId.of(USER_ID))
                .username(username)
                .password(BcryptUtil.bcryptHash("placeholder"))
                .status((short) 1)
                .source(UserEntity.SOURCE_OIDC)
                .build();
    }

    private static UserEntity disabledUser(String username) {
        return UserEntity.builder()
                .id(UserId.of(USER_ID))
                .username(username)
                .password(BcryptUtil.bcryptHash("placeholder"))
                .status((short) 2)
                .build();
    }

    private static ExternalAccount externalAccount() {
        return new ExternalAccount(SUB, "zhangsan", "张三",
                "zhangsan@example.com", "13800000000", null, Map.of());
    }

    /**
     * 解出 JWT payload（不验签，仅断言 claim）。
     */
    private static String payload(String jwt) {
        return JwtUtils.parsePayload(jwt).orElseThrow(() -> new IllegalStateException("invalid jwt"));
    }
}
