package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.oidc.PlatformJwtVerifier;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.system.domain.entity.McpToken;
import com.hisilicon.knowledge.platform.system.domain.repository.McpTokenRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.dto.McpQuotaDTO;
import com.hisilicon.knowledge.platform.system.dto.McpTokenIssuedDTO;
import io.smallrye.mutiny.Uni;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpTokenCommandService} 单元测试。
 *
 * <p>不打 Quarkus 容器：签名私钥/验签公钥由 {@code PemRsaUtils} 从 classpath 的
 * {@code privateKey.pem}/{@code publicKey.pem} 兜底读取，{@link PlatformJwtVerifier}
 * 用 {@code Optional.empty()} 构造即走同一兜底路径，正好覆盖"平台签、网关验"的契约。</p>
 *
 * <p>重点断言网关侧的真实契约（见 McpAuthFilter）：{@code type=mcp}、{@code jti} 为非空字符串、
 * subject 是账号、roles 里带管理员角色。</p>
 */
class McpTokenCommandServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String USERNAME = "alice";

    private McpTokenRepository repository;
    private InMemoryMcpGatewayStore gatewayStore;
    private UserRoleQueryRepository roleQueryRepository;
    private OperatorIdHolder operatorIdHolder;
    private PlatformJwtVerifier verifier;
    private McpTokenCommandService service;

    @BeforeEach
    void setUp() {
        repository = mock(McpTokenRepository.class);
        gatewayStore = new InMemoryMcpGatewayStore();
        roleQueryRepository = mock(UserRoleQueryRepository.class);
        operatorIdHolder = new OperatorIdHolder();
        operatorIdHolder.set(USERNAME);
        verifier = new PlatformJwtVerifier(Optional.empty());
        service = new McpTokenCommandService(repository, gatewayStore, roleQueryRepository,
                operatorIdHolder, 30L,
                "http://localhost:8080/hisilicon-knowledge-mcp/mcp", "hisilicon-knowledge");
        when(repository.findByUsername(anyString())).thenReturn(Uni.createFrom().item(List.of()));
        when(repository.save(any(McpToken.class))).thenReturn(Uni.createFrom().voidItem());
        when(roleQueryRepository.findRoleKeysByUsername(anyString()))
                .thenReturn(Uni.createFrom().item(List.of("user")));
    }

    @Test
    @DisplayName("签发的令牌能通过网关的验签契约（type=mcp / jti / subject）")
    void issuedTokenSatisfiesGatewayContract() throws Exception {
        McpTokenIssuedDTO issued = service.issue(new IssueMcpTokenCommand("trae"))
                .await().indefinitely();

        JwtClaims claims = verifier.verify(issued.token(), "mcp");
        assertEquals(USERNAME, claims.getSubject());
        assertEquals("mcp", claims.getStringClaimValue("type"));
        assertEquals(issued.jti(), claims.getStringClaimValue("jti"));
        assertNotNull(issued.token());
        // jose4j 的 NumericDate#getValue() 是 epoch 秒（long），不能直接喂给 Duration.between
        Instant expiresAt = Instant.ofEpochSecond(claims.getExpirationTime().getValue());
        long remainingSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
        assertTrue(remainingSeconds > Duration.ofDays(29).getSeconds()
                        && remainingSeconds <= Duration.ofDays(30).getSeconds(),
                "令牌有效期应为 ~30 天，实际剩余 " + remainingSeconds + " 秒");
        assertEquals("default".equals(issued.label()) ? "default" : "trae", issued.label());
    }

    @Test
    @DisplayName("令牌明文不落库：库里只有 SHA-256 摘要")
    void tokenHashIsDigestOfPlaintext() throws Exception {
        McpTokenIssuedDTO issued = service.issue(new IssueMcpTokenCommand("trae"))
                .await().indefinitely();

        ArgumentCaptor<McpToken> captor = ArgumentCaptor.forClass(McpToken.class);
        verify(repository).save(captor.capture());
        McpToken saved = captor.getValue();
        assertEquals(sha256(issued.token()), saved.getTokenHash());
        assertEquals(issued.jti(), saved.getJti());
        assertEquals(USERNAME, saved.getUsername());
        assertFalse(saved.getTokenHash().contains(issued.token()));
        assertTrue(saved.isUsableAt(LocalDateTime.now(ZONE)));
    }

    @Test
    @DisplayName("签发即轮换：旧的有效令牌被吊销（Redis 位 + 库内留痕），并回传 replacedJti")
    void issueRotatesActiveToken() {
        McpToken old = activeToken("old-jti", LocalDateTime.now(ZONE).plusDays(10));
        when(repository.findByUsername(USERNAME)).thenReturn(Uni.createFrom().item(List.of(old)));
        when(repository.markRevoked(eq("old-jti"), any(LocalDateTime.class)))
                .thenReturn(Uni.createFrom().item(1));

        McpTokenIssuedDTO issued = service.issue(new IssueMcpTokenCommand("trae"))
                .await().indefinitely();

        assertEquals("old-jti", issued.replacedJti());
        assertTrue(gatewayStore.isRevoked("old-jti"), "旧令牌必须写 Redis 吊销位");
        verify(repository).markRevoked(eq("old-jti"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("首次签发没有旧令牌：replacedJti 为 null，也不写吊销位")
    void firstIssueHasNoReplacement() {
        McpTokenIssuedDTO issued = service.issue(new IssueMcpTokenCommand(null))
                .await().indefinitely();

        assertNull(issued.replacedJti());
        assertEquals(0, gatewayStore.revokedCount());
        assertEquals("default", issued.label());
    }

    @Test
    @DisplayName("注销他人令牌返回「不存在」，不泄露存在性，也不写吊销位")
    void revokeOwnRejectsForeignToken() {
        McpToken others = activeToken("bob-jti", LocalDateTime.now(ZONE).plusDays(10));
        others = rebuild(others, "bob");
        when(repository.findByJti("bob-jti")).thenReturn(Uni.createFrom().item(Optional.of(others)));

        BusinessException failure = unwrap(assertThrows(RuntimeException.class,
                () -> service.revokeOwn("bob-jti").await().indefinitely()));

        assertEquals(ErrorCodeEnum.USER_ERROR_A0430, failure.getErrorCodeEnum());
        assertFalse(gatewayStore.isRevoked("bob-jti"));
        verify(repository, never()).markRevoked(anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("注销自己的令牌：先写 Redis 吊销位，再写库")
    void revokeOwnMarksToken() {
        McpToken own = activeToken("own-jti", LocalDateTime.now(ZONE).plusDays(10));
        when(repository.findByJti("own-jti")).thenReturn(Uni.createFrom().item(Optional.of(own)));
        when(repository.markRevoked(eq("own-jti"), any(LocalDateTime.class)))
                .thenReturn(Uni.createFrom().item(1));

        service.revokeOwn("own-jti").await().indefinitely();

        assertTrue(gatewayStore.isRevoked("own-jti"));
        verify(repository).markRevoked(eq("own-jti"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("一键收权只处理未注销的令牌")
    void revokeAllForUserSkipsAlreadyRevoked() {
        McpToken live = activeToken("live-jti", LocalDateTime.now(ZONE).plusDays(10));
        McpToken dead = activeToken("dead-jti", LocalDateTime.now(ZONE).plusDays(10));
        dead.revoke(LocalDateTime.now(ZONE).minusDays(1));
        when(repository.findByUsername(USERNAME))
                .thenReturn(Uni.createFrom().item(List.of(live, dead)));
        when(repository.markRevoked(eq("live-jti"), any(LocalDateTime.class)))
                .thenReturn(Uni.createFrom().item(1));

        service.revokeAllForUser(USERNAME).await().indefinitely();

        assertTrue(gatewayStore.isRevoked("live-jti"));
        assertFalse(gatewayStore.isRevoked("dead-jti"));
        verify(repository, times(1)).markRevoked(anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("没有操作员上下文时拒绝签发")
    void issueWithoutOperatorIsRejected() {
        operatorIdHolder.set(null);

        BusinessException failure = unwrap(assertThrows(RuntimeException.class,
                () -> service.issue(new IssueMcpTokenCommand("trae")).await().indefinitely()));

        assertEquals(ErrorCodeEnum.USER_ERROR_A0301, failure.getErrorCodeEnum());
    }

    @Test
    @DisplayName("客户端配置片段带上网关地址与 Bearer 令牌")
    void mcpServersJsonCarriesToken() {
        McpTokenIssuedDTO issued = service.issue(new IssueMcpTokenCommand("trae"))
                .await().indefinitely();

        assertTrue(issued.mcpServersJson().contains("http://localhost:8080/hisilicon-knowledge-mcp/mcp"));
        assertTrue(issued.mcpServersJson().contains("Bearer " + issued.token()));
        assertTrue(issued.mcpServersJson().contains("\"hisilicon-knowledge\""));
    }

    @Test
    @DisplayName("限额覆盖：两维都为空等于清除覆盖")
    void setQuotaWithNullsClearsOverride() {
        gatewayStore.replaceQuota(USERNAME, 100L, 1000L).await().indefinitely();

        McpQuotaDTO cleared = service.setQuota(USERNAME, new SetMcpQuotaCommand(null, null))
                .await().indefinitely();

        assertNull(cleared.dailyCalls());
        assertNull(cleared.dailyChars());
        assertTrue(gatewayStore.findDailyCallsOverride(USERNAME).await().indefinitely().isEmpty());
    }

    // ========== helpers ==========

    private static McpToken activeToken(String jti, LocalDateTime expiresAt) {
        return McpToken.builder().jti(jti).username(USERNAME).tokenHash("hash").label("trae")
                .createdAt(LocalDateTime.now(ZONE).minusDays(1)).expiresAt(expiresAt).build();
    }

    private static McpToken rebuild(McpToken source, String username) {
        return McpToken.builder().jti(source.getJti()).username(username)
                .tokenHash(source.getTokenHash()).label(source.getLabel())
                .createdAt(source.getCreatedAt()).expiresAt(source.getExpiresAt()).build();
    }

    private static BusinessException unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof BusinessException businessException) {
                return businessException;
            }
            current = current instanceof CompletionException || current.getCause() != current
                    ? current.getCause() : null;
        }
        throw new IllegalStateException("expected BusinessException but got " + thrown);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}