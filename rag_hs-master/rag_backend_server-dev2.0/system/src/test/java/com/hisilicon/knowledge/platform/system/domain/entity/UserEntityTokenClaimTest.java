package com.hisilicon.knowledge.platform.system.domain.entity;

import com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserEntity} 平台 JWT 的 {@code roles}/{@code source} claim 断言。
 *
 * <p>不依赖 Quarkus 与 classpath 私钥：用运行时生成的 RSA 密钥对签发真实 JWT，再解 payload 断言 claim。</p>
 */
class UserEntityTokenClaimTest {

    private static PrivateKey signingKey;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        signingKey = keyPair.getPrivate();
    }

    @Test
    @DisplayName("签发 OIDC 令牌：access/refresh 都带 roles 列表与 source=oidc")
    void shouldCarryRolesAndOidcSource() {
        TokenDTO token = UserEntity.generateTokenWithKey(
                "oa_1a2b3c4d", signingKey, List.of("community", "sys_admin"), UserEntity.SOURCE_OIDC);

        String accessPayload = payload(token.accessToken());
        String refreshPayload = payload(token.refreshToken());

        assertTrue(accessPayload.contains("\"roles\":[\"community\",\"sys_admin\"]"), accessPayload);
        assertTrue(accessPayload.contains("\"source\":\"oidc\""), accessPayload);
        assertTrue(refreshPayload.contains("\"roles\":[\"community\",\"sys_admin\"]"), refreshPayload);
        assertTrue(refreshPayload.contains("\"source\":\"oidc\""), refreshPayload);
        assertEquals("oa_1a2b3c4d", JwtUtils.extractSubject(token.accessToken()).orElseThrow());
    }

    @Test
    @DisplayName("默认签发（本地账号口径）：roles 为空数组而不是省略，source=local")
    void shouldEmitEmptyRolesAndLocalSourceByDefault() {
        TokenDTO token = UserEntity.generateTokenWithKey("admin", signingKey);

        String refreshPayload = payload(token.refreshToken());

        assertTrue(refreshPayload.contains("\"roles\":[]"), refreshPayload);
        assertTrue(refreshPayload.contains("\"source\":\"local\""), refreshPayload);
    }

    private static String payload(String jwt) {
        return JwtUtils.parsePayload(jwt).orElseThrow(() -> new IllegalStateException("invalid jwt"));
    }
}
