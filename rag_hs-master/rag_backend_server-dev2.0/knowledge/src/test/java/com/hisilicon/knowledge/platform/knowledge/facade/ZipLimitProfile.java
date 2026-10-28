package com.hisilicon.knowledge.platform.knowledge.facade;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * ZIP 大小校验测试专用 profile：业务单文件上限调小为 1M，HTTP body 限制放宽到 50M
 * （覆盖全局测试配置的 1M，让 2MB body 能到达业务层）。
 */
public class ZipLimitProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "kb.upload.max-file-size", "1M",
                "quarkus.http.limits.max-body-size", "50M"
        );
    }
}
