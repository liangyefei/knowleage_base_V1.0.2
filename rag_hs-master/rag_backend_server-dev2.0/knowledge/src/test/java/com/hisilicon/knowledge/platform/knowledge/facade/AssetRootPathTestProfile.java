package com.hisilicon.knowledge.platform.knowledge.facade;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * 图片资产「根路径前缀」测试专用 profile：注入签名密钥 + 真实的 {@code quarkus.http.root-path}，
 * 复现生产部署形态（本应用 API 全量挂在 {@code /hisilicon-knowledge} 之下）。
 * <p>回归目标：重写生成的资产 URL 必须携带根路径前缀，否则浏览器请求 {@code /kb/...} 会漏掉
 * 根路径一级而命中 404（此 bug 在无 root-path 的单元测试环境下不会暴露）。</p>
 */
public class AssetRootPathTestProfile implements QuarkusTestProfile {

    /** 测试签名密钥（与 AssetSignatureTestProfile 一致） */
    public static final String TEST_SECRET = AssetSignatureTestProfile.TEST_SECRET;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "kb.asset.signature-secret", TEST_SECRET,
                "kb.asset.url-expiry-seconds", "3600",
                "quarkus.http.root-path", "/hisilicon-knowledge"
        );
    }
}