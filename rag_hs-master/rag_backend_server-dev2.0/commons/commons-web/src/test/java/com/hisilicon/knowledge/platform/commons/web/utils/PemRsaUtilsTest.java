package com.hisilicon.knowledge.platform.commons.web.utils;

import com.hisilicon.knowledge.platform.commons.web.exception.DecryptException;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PemRsaUtils 私钥解析容错测试。
 * <p>2026-08-13 生产实测：网站导出工具生成"头 PKCS#1（BEGIN RSA PRIVATE KEY）+ 体 PKCS#8"的
 * 格式错配私钥，旧实现按 PEM 头分派解析 → malformed sequence。本测试验证按内容嗅探解析的兼容性。</p>
 */
class PemRsaUtilsTest {

    private static KeyPair keyPair;
    private static String publicKeyPem;
    /** 标准 PKCS#8（BEGIN PRIVATE KEY） */
    private static String pkcs8Pem;
    /** 标准 PKCS#1（BEGIN RSA PRIVATE KEY） */
    private static String pkcs1Pem;
    /** 格式错配：头 PKCS#1 + 体 PKCS#8（用户生产场景） */
    private static String mismatchedPem;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();

        // 公钥 PEM（SPKI）
        byte[] spki = keyPair.getPublic().getEncoded();
        publicKeyPem = wrapPem("PUBLIC KEY", spki);

        // PKCS#8 私钥 PEM
        byte[] pkcs8 = privateKey.getEncoded();
        pkcs8Pem = wrapPem("PRIVATE KEY", pkcs8);

        // PKCS#1 私钥 PEM：从 PKCS#8 提取 RSAPrivateKey
        PrivateKeyInfo pki = PrivateKeyInfo.getInstance(pkcs8);
        byte[] pkcs1 = pki.parsePrivateKey().toASN1Primitive().getEncoded();
        pkcs1Pem = wrapPem("RSA PRIVATE KEY", pkcs1);

        // 格式错配：PKCS#1 头 + PKCS#8 体（模拟网站工具 bug）
        mismatchedPem = wrapPem("RSA PRIVATE KEY", pkcs8);
    }

    private static String wrapPem(String label, byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    private String encrypt(String plain) {
        return PemRsaUtils.encryptBase64WithKey(plain, "zh-CN", publicKeyPem);
    }

    /** 格式错配（头 PKCS#1 + 体 PKCS#8）：必须能解密 —— 用户生产场景 */
    @Test
    void shouldDecryptWithMismatchedHeaderPkcs1BodyPkcs8() {
        String cipher = encrypt("hello-mismatch");
        String plain = PemRsaUtils.decryptStrWithKey(cipher, "zh-CN", mismatchedPem);
        assertEquals("hello-mismatch", plain);
    }

    /** 标准 PKCS#8：正常解密 */
    @Test
    void shouldDecryptWithPkcs8() {
        String cipher = encrypt("hello-pkcs8");
        String plain = PemRsaUtils.decryptStrWithKey(cipher, "zh-CN", pkcs8Pem);
        assertEquals("hello-pkcs8", plain);
    }

    /** 标准 PKCS#1：正常解密 */
    @Test
    void shouldDecryptWithPkcs1() {
        String cipher = encrypt("hello-pkcs1");
        String plain = PemRsaUtils.decryptStrWithKey(cipher, "zh-CN", pkcs1Pem);
        assertEquals("hello-pkcs1", plain);
    }

    /** 加密私钥（带口令）：拒绝并抛业务异常 */
    @Test
    void shouldRejectEncryptedPrivateKey() {
        String encryptedPem = "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(new byte[64]) + "\n"
                + "-----END ENCRYPTED PRIVATE KEY-----\n";
        DecryptException ex = assertThrows(DecryptException.class,
                () -> PemRsaUtils.decryptStrWithKey(encrypt("x"), "zh-CN", encryptedPem));
        assertTrue(ex.getMessage().contains("Encrypted") || ex.getMessage().contains("encrypted"),
                "错误消息应提示加密私钥不支持, 实际: " + ex.getMessage());
    }

    /** 乱码私钥：拒绝并抛业务异常 */
    @Test
    void shouldRejectGarbagePrivateKey() {
        String garbage = "-----BEGIN RSA PRIVATE KEY-----\nYWJjZGVmZ2hpamtsbW5vcA==\n-----END RSA PRIVATE KEY-----\n";
        assertThrows(DecryptException.class,
                () -> PemRsaUtils.decryptStrWithKey(encrypt("x"), "zh-CN", garbage));
    }

    /** 签名路径同样兼容格式错配私钥 */
    @Test
    void shouldSignWithMismatchedPrivateKey() {
        String sig = PemRsaUtils.signWithKey("data-to-sign", "zh-CN", mismatchedPem);
        assertTrue(sig != null && !sig.isBlank());
        // 用公钥验签
        boolean ok = PemRsaUtils.verifyWithKey("data-to-sign", sig, "zh-CN", publicKeyPem);
        assertTrue(ok, "格式错配私钥签名应可通过对应公钥验证");
    }
}
