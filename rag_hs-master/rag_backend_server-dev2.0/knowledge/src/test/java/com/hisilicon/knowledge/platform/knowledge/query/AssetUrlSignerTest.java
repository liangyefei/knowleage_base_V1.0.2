package com.hisilicon.knowledge.platform.knowledge.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片资产签名器单元测试（纯 JUnit，无容器）
 * <p>覆盖：签名确定性/载荷敏感性/格式、verify 往返、过期、篡改（path/documentId/expires/sig）、
 * 密钥未配置的降级策略（拒绝签发/校验）。</p>
 */
class AssetUrlSignerTest {

    private static final String SECRET = "unit-test-secret";
    private static final String DOC = "doc-1";
    private static final String PATH = "kb/kb-1/docs/images/图1.png";

    private final AssetUrlSigner signer = new AssetUrlSigner(SECRET, 3600, "");

    @Test
    void shouldBeConfiguredWithSecret() {
        assertTrue(signer.isConfigured(), "配置了密钥应视为已配置");
        assertEquals(3600, signer.urlExpirySeconds());
    }

    @Test
    void shouldNotBeConfiguredWhenSecretBlank() {
        assertFalse(new AssetUrlSigner("", 3600, "").isConfigured(), "空密钥未配置");
        assertFalse(new AssetUrlSigner("   ", 3600, "").isConfigured(), "空白密钥未配置");
    }

    @Test
    void shouldSignDeterministicallyInLowercaseHex() {
        String sig1 = signer.sign(DOC, PATH, 1000L);
        String sig2 = signer.sign(DOC, PATH, 1000L);
        assertEquals(sig1, sig2, "同载荷签名必须确定");
        assertEquals(64, sig1.length(), "HMAC-SHA256 小写 hex 长 64");
        assertEquals(sig1, sig1.toLowerCase(java.util.Locale.ROOT), "hex 应为小写");
    }

    @Test
    void shouldExpireImmediatelyAtNow() {
        long expires = System.currentTimeMillis() / 1000;
        String sig = signer.sign(DOC, PATH, expires);
        assertFalse(signer.verify(DOC, PATH, expires, sig), "expires=当前秒应视为过期（<= now）");
    }

    @Test
    void shouldVerifyRoundTrip() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String sig = signer.sign(DOC, PATH, expires);
        assertTrue(signer.verify(DOC, PATH, expires, sig), "合法签名 + 未过期应通过");
    }

    @Test
    void shouldRejectTamperedPath() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String sig = signer.sign(DOC, PATH, expires);
        assertFalse(signer.verify(DOC, "kb/kb-1/docs/images/图2.png", expires, sig),
                "路径被篡改应拒绝");
    }

    @Test
    void shouldRejectTamperedDocumentId() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String sig = signer.sign(DOC, PATH, expires);
        assertFalse(signer.verify("doc-2", PATH, expires, sig),
                "documentId 被篡改（跨文档盗链）应拒绝");
    }

    @Test
    void shouldRejectExpiredSignature() {
        long expires = System.currentTimeMillis() / 1000 - 1;
        String sig = signer.sign(DOC, PATH, expires);
        assertFalse(signer.verify(DOC, PATH, expires, sig), "过期签名应拒绝");
    }

    @Test
    void shouldRejectMissingOrBlankSignature() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        assertFalse(signer.verify(DOC, PATH, expires, null), "缺失签名应拒绝");
        assertFalse(signer.verify(DOC, PATH, expires, "  "), "空白签名应拒绝");
    }

    @Test
    void shouldDifferForDifferentPayloads() {
        long expires = 1000L;
        assertNotEquals(signer.sign(DOC, PATH, expires), signer.sign(DOC, PATH, expires + 1),
                "expires 变化签名应变化");
        assertNotEquals(signer.sign(DOC, PATH, expires), signer.sign("doc-2", PATH, expires),
                "documentId 变化签名应变化");
    }

    @Test
    void shouldRefuseSigningWhenUnconfigured() {
        AssetUrlSigner unconfigured = new AssetUrlSigner("", 3600, "");
        assertThrows(IllegalStateException.class, () -> unconfigured.sign(DOC, PATH, 1000L),
                "未配置密钥时拒绝签发");
        assertFalse(unconfigured.verify(DOC, PATH, 2000L, "any-sig"),
                "未配置密钥时校验恒 false（拒绝校验）");
    }

    @Test
    void shouldComputeExpiryFromNowPlusConfiguredSeconds() {
        long before = System.currentTimeMillis() / 1000;
        long expiry = signer.expiryEpochSecondNow();
        long after = System.currentTimeMillis() / 1000;
        assertTrue(expiry >= before + 3600 && expiry <= after + 3600,
                "过期时间 = now + url-expiry-seconds");
    }

    // ======================== 对话兜底签名域（/kb/chat/assets） ========================

    @Test
    void shouldVerifyChatAssetRoundTrip() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "mineru_output/1c25e985f396984e/images/page_0_image_31.jpg";
        String sig = signer.signChatAsset(path, expires);

        assertTrue(signer.verifyChatAsset(path, expires, sig), "合法兜底签名 + 未过期应通过");
        assertEquals(64, sig.length(), "HMAC-SHA256 小写 hex 长 64");
    }

    @Test
    void shouldRejectChatAssetOnTamperedPathOrExpiry() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "mineru_output/a/page_0_image_31.jpg";
        String sig = signer.signChatAsset(path, expires);

        assertFalse(signer.verifyChatAsset("mineru_output/a/page_0_image_32.jpg", expires, sig),
                "路径被篡改应拒绝");
        assertFalse(signer.verifyChatAsset(path, expires + 1, sig), "expires 被篡改应拒绝");
        assertFalse(signer.verifyChatAsset(path, expires, null), "缺失签名应拒绝");
        assertFalse(signer.verifyChatAsset(path, expires, "  "), "空白签名应拒绝");
    }

    @Test
    void shouldRejectExpiredChatAssetSignature() {
        long expires = System.currentTimeMillis() / 1000 - 1;
        String path = "mineru_output/a/page_0_image_31.jpg";
        assertFalse(signer.verifyChatAsset(path, expires, signer.signChatAsset(path, expires)),
                "过期兜底签名应拒绝");
    }

    @Test
    void shouldRefuseChatAssetSigningWhenUnconfigured() {
        AssetUrlSigner unconfigured = new AssetUrlSigner("", 3600, "");
        assertThrows(IllegalStateException.class,
                () -> unconfigured.signChatAsset(PATH, 1000L), "未配置密钥时拒绝签发兜底签名");
        assertFalse(unconfigured.verifyChatAsset(PATH, 2000L, "any-sig"),
                "未配置密钥时兜底校验恒 false");
    }

    /**
     * 域隔离（安全关键）：文档图片域与对话兜底域签名<b>不可互用</b>——跨端点重放必须失败。
     * <p>否则攻击者可拿 markdown 下载页签发的"文档域"URL 参数去调 {@code /kb/chat/assets}，
     * 或反向操作，绕过各自端点的路径校验（文档域收敛在文档子树内、兜底域收敛在白名单目录内）。</p>
     */
    @Test
    void shouldNotCrossVerifyBetweenSignatureDomains() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "mineru_output/a/page_0_image_31.jpg";

        // 用文档域（documentId=固定标签）签发 → 兜底域必须拒绝
        assertFalse(signer.verifyChatAsset(path, expires, signer.sign("chat-asset", path, expires)),
                "文档域签名不得通过兜底域校验（即便 documentId 恰好等于域标签字符串）");
        // 用兜底域签发 → 文档域必须拒绝
        assertFalse(signer.verify("chat-asset", path, expires, signer.signChatAsset(path, expires)),
                "兜底域签名不得通过文档域校验");
        // 同一路径下两域签名互不相同
        assertNotEquals(signer.sign(DOC, path, expires), signer.signChatAsset(path, expires),
                "两域在同一路径上的签名应不同");
    }
}
