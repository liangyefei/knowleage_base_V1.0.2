package com.hisilicon.knowledge.platform.commons.oidc;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * 平台 JWT 本地验签器（资源服务器侧）。
 *
 * <p>以配置的 RSA 公钥（MP Config 键 {@code rsa.public-key}，PEM / SPKI "BEGIN PUBLIC KEY"，
 * 未配置时回退 classpath {@code publicKey.pem}，读取方式参照 {@code commons-web} 的
 * {@code PemRsaUtils}）本地完成 <b>RS256 验签 + exp 过期校验 + 自定义 claim {@code type}
 * 必须存在</b>；未通过一律 fail-closed 抛 {@link BusinessException}：</p>
 * <ul>
 *   <li>签名不符 / 格式错误 / 缺少 {@code type} → {@code A0342 平台令牌无效}；</li>
 *   <li>已过期 → {@code A0343 平台令牌已过期}。</li>
 * </ul>
 *
 * <p>公钥不可用（配置与 classpath 均缺失）时按系统配置缺失处理（B0601）。</p>
 *
 * <p>取代 {@code commons-web} {@code AuthenticationFilter} 现阶段"只 Base64 解析不验签"
 * 的过渡行为：接入方应在本验签器通过后再信任 token 中的 sub 等声明。</p>
 */
@ApplicationScoped
public class PlatformJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(PlatformJwtVerifier.class);

    /** 平台 JWT 的自定义 claim：令牌类型标识 */
    public static final String CLAIM_TYPE = "type";

    private static final int CLOCK_SKEW_SECONDS = 30;
    private static final String RSA_ALGORITHM = "RSA";
    private static final String DEFAULT_PUBLIC_KEY_RESOURCE = "publicKey.pem";

    private final Optional<String> configuredPublicKeyPem;
    private volatile JwtConsumer jwtConsumer;

    /**
     * CDI 构造函数。
     *
     * @param publicKeyPem RSA 公钥 PEM（MP Config 键 {@code rsa.public-key}，可选；
     *                     缺省回退 classpath {@code publicKey.pem}）
     */
    public PlatformJwtVerifier(
            @ConfigProperty(name = "rsa.public-key") Optional<String> publicKeyPem) {
        this.configuredPublicKeyPem = publicKeyPem.filter(pem -> !pem.isBlank());
    }

    /**
     * 验签并返回 claim 集合（要求存在自定义 claim {@code type}）。
     *
     * @param token 平台签发的 RS256 JWT
     * @return 验签通过后的 {@link org.jose4j.jwt.JwtClaims}
     */
    public org.jose4j.jwt.JwtClaims verify(String token) {
        return verify(token, null);
    }

    /**
     * 验签并返回 claim 集合，同时校验自定义 claim {@code type}。
     *
     * @param token        平台签发的 RS256 JWT
     * @param expectedType 期望的 {@code type} 值；{@code null} 表示仅要求该 claim 存在
     * @return 验签通过后的 {@link org.jose4j.jwt.JwtClaims}
     */
    public org.jose4j.jwt.JwtClaims verify(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.OIDC_TOKEN_INVALID);
        }
        org.jose4j.jwt.JwtClaims claims = processToClaims(token.trim());
        checkTypeClaim(claims, expectedType);
        return claims;
    }

    private org.jose4j.jwt.JwtClaims processToClaims(String token) {
        try {
            return consumer().processToClaims(token);
        } catch (InvalidJwtException e) {
            if (e.hasExpired()) {
                log.info("Platform JWT is expired");
                throw new BusinessException(ErrorCodeEnum.OIDC_TOKEN_EXPIRED);
            }
            log.info("Platform JWT verification failed: {}", e.getMessage());
            throw new BusinessException(ErrorCodeEnum.OIDC_TOKEN_INVALID,
                    "Platform token verification failed", e);
        }
    }

    private void checkTypeClaim(org.jose4j.jwt.JwtClaims claims, String expectedType) {
        // 用无受检异常的取值方式，再自行做类型判定（jose4j 的 typed getter 会抛 MalformedClaimException）
        Object rawType = claims.getClaimValue(CLAIM_TYPE);
        if (!(rawType instanceof String type) || type.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.OIDC_TOKEN_INVALID,
                    "Platform token is missing claim '" + CLAIM_TYPE + "'");
        }
        if (expectedType != null && !expectedType.equals(type)) {
            throw new BusinessException(ErrorCodeEnum.OIDC_TOKEN_INVALID,
                    "Platform token type mismatch, expected '" + expectedType + "' but got '" + type + "'");
        }
    }

    private JwtConsumer consumer() {
        JwtConsumer local = jwtConsumer;
        if (local == null) {
            synchronized (this) {
                local = jwtConsumer;
                if (local == null) {
                    local = buildConsumer(resolvePublicKeyPem());
                    jwtConsumer = local;
                }
            }
        }
        return local;
    }

    /** 公钥解析：配置优先，classpath {@code publicKey.pem} 兜底（参照 PemRsaUtils 双通道读取） */
    private String resolvePublicKeyPem() {
        if (configuredPublicKeyPem.isPresent()) {
            return configuredPublicKeyPem.get();
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (var is = cl.getResourceAsStream(DEFAULT_PUBLIC_KEY_RESOURCE)) {
            if (is == null) {
                log.error("Public key not found: config 'rsa.public-key' is unset and classpath resource '{}' is missing",
                        DEFAULT_PUBLIC_KEY_RESOURCE);
                throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0601,
                        "Public key not found: config 'rsa.public-key' is unset and classpath resource '"
                                + DEFAULT_PUBLIC_KEY_RESOURCE + "' is missing");
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.error("Failed to read public key resource {}", DEFAULT_PUBLIC_KEY_RESOURCE, e);
            throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0601,
                    "Failed to read public key resource: " + DEFAULT_PUBLIC_KEY_RESOURCE, e);
        }
    }

    private static JwtConsumer buildConsumer(String publicKeyPem) {
        // jose4j 默认强制要求签名（仅 setDisableRequireSignature / setSkipSignatureVerification 才关闭），
        // 这里再加 JWS 算法白名单 RS256，杜绝 alg=none 与算法混淆。
        return new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setAllowedClockSkewInSeconds(CLOCK_SKEW_SECONDS)
                .setVerificationKey(parseRsaPublicKey(publicKeyPem))
                .setJwsAlgorithmConstraints(new AlgorithmConstraints(
                        AlgorithmConstraints.ConstraintType.WHITELIST, "RS256"))
                .build();
    }

    /** 解析 PEM（SPKI，{@code -----BEGIN PUBLIC KEY-----}）为 RSA 公钥 */
    private static PublicKey parseRsaPublicKey(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance(RSA_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            log.error("Failed to parse RSA public key PEM", e);
            throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0601,
                    "Invalid RSA public key PEM configured for JWT verification", e);
        }
    }
}
