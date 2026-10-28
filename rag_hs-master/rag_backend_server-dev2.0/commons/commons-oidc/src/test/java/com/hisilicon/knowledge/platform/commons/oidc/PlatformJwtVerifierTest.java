package com.hisilicon.knowledge.platform.commons.oidc;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link PlatformJwtVerifier} 验签/过期/篡改三态测试（测试自生成 RSA 密钥对）。 */
class PlatformJwtVerifierTest {

    private static final String TOKEN_TYPE = "platform";
    private static final String SUBJECT = "user-1001";

    private static RSAPrivateKey privateKey;
    private static String publicKeyPem;
    private static PlatformJwtVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        verifier = new PlatformJwtVerifier(Optional.of(publicKeyPem));
    }

    @Test
    void verifyAcceptsValidSignedToken() throws Exception {
        String token = signToken(numericDateNowPlusSeconds(600));
        JwtClaims claims = verifier.verify(token, TOKEN_TYPE);
        assertEquals(SUBJECT, claims.getSubject());
        assertEquals(TOKEN_TYPE, claims.getClaimValue(PlatformJwtVerifier.CLAIM_TYPE, String.class));
        assertNotNull(claims);
    }

    @Test
    void verifyRejectsExpiredToken() {
        String token = signToken(numericDateNowPlusSeconds(-3600));
        BusinessException e = assertThrows(BusinessException.class, () -> verifier.verify(token));
        assertEquals(ErrorCodeEnum.OIDC_TOKEN_EXPIRED, e.getErrorCodeEnum());
    }

    @Test
    void verifyRejectsTamperedToken() {
        String token = signToken(numericDateNowPlusSeconds(600));
        String tampered = token.substring(0, token.length() - 2) + "xx";
        BusinessException e = assertThrows(BusinessException.class,
                () -> verifier.verify(tampered, TOKEN_TYPE));
        assertEquals(ErrorCodeEnum.OIDC_TOKEN_INVALID, e.getErrorCodeEnum());
    }

    @Test
    void verifyRejectsTokenWithoutTypeClaim() {
        JwtClaims jwtClaims = new JwtClaims();
        jwtClaims.setSubject(SUBJECT);
        jwtClaims.setExpirationTime(numericDateNowPlusSeconds(600));
        String token = sign(jwtClaims.toJson());
        BusinessException e = assertThrows(BusinessException.class,
                () -> verifier.verify(token, TOKEN_TYPE));
        assertEquals(ErrorCodeEnum.OIDC_TOKEN_INVALID, e.getErrorCodeEnum());
    }

    @Test
    void verifyRejectsNullToken() {
        BusinessException e = assertThrows(BusinessException.class, () -> verifier.verify(null));
        assertEquals(ErrorCodeEnum.OIDC_TOKEN_INVALID, e.getErrorCodeEnum());
    }

    private static NumericDate numericDateNowPlusSeconds(long seconds) {
        // addSeconds 是原地修改返回 void，用 fromMilliseconds 构造新实例
        return NumericDate.fromMilliseconds(System.currentTimeMillis() + seconds * 1000L);
    }

    private static String signToken(NumericDate expiration) {
        JwtClaims claims = new JwtClaims();
        claims.setSubject(SUBJECT);
        claims.setExpirationTime(expiration);
        claims.setClaim(PlatformJwtVerifier.CLAIM_TYPE, TOKEN_TYPE);
        return sign(claims.toJson());
    }

    private static String sign(String payload) {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(payload);
        jws.setKey(privateKey);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        try {
            return jws.getCompactSerialization();
        } catch (org.jose4j.lang.JoseException e) {
            throw new IllegalStateException("Test token signing failed", e);
        }
    }
}
