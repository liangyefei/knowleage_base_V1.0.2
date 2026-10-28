package com.hisilicon.knowledge.platform.knowledge.facade;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * 图片资产签名测试专用 profile：注入固定签名密钥（默认配置为空 = 功能降级，
 * 未配置时无法签发/校验），供 markdown 图片网络地址化相关测试启用签名能力。
 */
public class AssetSignatureTestProfile implements QuarkusTestProfile {

    /** 测试签名密钥（与测试内 AssetUrlSigner 实例保持一致） */
    public static final String TEST_SECRET = "asset-test-secret";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "kb.asset.signature-secret", TEST_SECRET,
                "kb.asset.url-expiry-seconds", "3600"
        );
    }
}
