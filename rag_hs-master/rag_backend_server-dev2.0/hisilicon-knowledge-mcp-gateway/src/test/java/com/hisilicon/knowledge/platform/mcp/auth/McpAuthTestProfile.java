package com.hisilicon.knowledge.platform.mcp.auth;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.lang.JoseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * 鉴权语义测试专用 profile：打开鉴权 + 注入**测试专用 RSA 公钥**。
 *
 * <p>密钥对是固化在 {@code src/test/resources/} 下的 {@code publicKey.pem} /
 * {@code privateKey.pem}，不动态生成。原因：曾用静态字段 {@code KeyPair} 动态生成，
 * 结果 profile 实例化与测试类调用 {@link #mintToken} 可能命中不同类加载器内的两份
 * {@code static} 字段，签名的私钥和配置的公钥不是一对，所有 token 都验签失败
 * （{@code JWT rejected due to invalid signature}）。落盘资源让签名与验签同源。</p>
 *
 * <p>存储仍用 %test 段选中的 memory 实现（{@code MemoryMcpUsageStore}），
 * 吊销/限额用例通过其测试钩子直接置状态。</p>
 */
public class McpAuthTestProfile implements QuarkusTestProfile {

    private static final String PUBLIC_KEY_RESOURCE = "/publicKey.pem";
    private static final String PRIVATE_KEY_RESOURCE = "/privateKey.pem";

    public static String publicKeyPem() {
        return readResource(PUBLIC_KEY_RESOURCE);
    }

    public static PrivateKey privateKey() {
        String pem = readResource(PRIVATE_KEY_RESOURCE);
        byte[] der = Base64.getMimeDecoder().decode(stripPemHeaderFooter(pem));
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("failed to parse test private key", e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "mcp-gateway.auth.enabled", "true",
                "rsa.public-key", publicKeyPem());
    }

    /**
     * 签发一个平台 MCP 专用 JWT（模拟 iam 侧 McpTokenCommandService 的产物）。
     *
     * @param username sub
     * @param jti      token 唯一 id（吊销/计量键）
     * @param roles    roles claim（逗号分隔，可空）
     */
    public static String mintToken(String username, String jti, String roles) {
        try {
            JwtClaims claims = new JwtClaims();
            claims.setIssuer("hisilicon-knowledge-platform");
            claims.setSubject(username);
            claims.setJwtId(jti);
            claims.setExpirationTimeMinutesInTheFuture(60);
            claims.setClaim("type", "mcp");
            if (roles != null && !roles.isBlank()) {
                claims.setClaim("roles", roles);
            }
            JsonWebSignature jws = new JsonWebSignature();
            jws.setPayload(claims.toJson());
            jws.setAlgorithmHeaderValue("RS256");
            jws.setKey(privateKey());
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException("failed to mint test token", e);
        }
    }

    /** 便于构造"过期 token"等非法形态 */
    public static String mintExpiredToken(String username, String jti) {
        try {
            JwtClaims claims = new JwtClaims();
            claims.setIssuer("hisilicon-knowledge-platform");
            claims.setSubject(username);
            claims.setJwtId(jti);
            NumericDate past = NumericDate.now();
            past.addSeconds(-3600);
            claims.setExpirationTime(past);
            claims.setClaim("type", "mcp");
            JsonWebSignature jws = new JsonWebSignature();
            jws.setPayload(claims.toJson());
            jws.setAlgorithmHeaderValue("RS256");
            jws.setKey(privateKey());
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException("failed to mint test token", e);
        }
    }

    private static String readResource(String name) {
        try (InputStream in = McpAuthTestProfile.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("test key resource not found on classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read test key resource: " + name, e);
        }
    }

    private static String stripPemHeaderFooter(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }
}