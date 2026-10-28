package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 字典缓存手动失效接口测试
 * <p>{@link DictMetaKeyCacheService#evictCache()} 在真实实现中同时 DEL 两个 key
 * （白名单 + 值缓存），生产环境调用一次即清两者；此处 mock 不级联到值缓存 mock，
 * 故仅断言 {@code dictMetaKeyCacheService.evictCache()} 被调用一次。</p>
 */
@QuarkusTest
class DictCacheResourceTest {

    private static final String VALID_TOKEN = buildToken("admin", System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DictMetaKeyCacheService dictMetaKeyCacheService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDictMetaKeyCacheService(), dictMetaKeyCacheService);
        MockDictMetaKeyCacheService.reset();
    }

    @Test
    void shouldRefreshCache() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().post("/system/dict/cache/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));
        // 手动失效调用 DictMetaKeyCacheService.evictCache()（生产中删两个 key）
        assertEquals(1, MockDictMetaKeyCacheService.getEvictCount());
    }

    @Test
    void shouldRequireAuth() {
        given()
                .contentType(ContentType.JSON)
                .when().post("/system/dict/cache/refresh")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"));
        // 鉴权失败不触发失效
        assertEquals(0, MockDictMetaKeyCacheService.getEvictCount());
    }

    private static String buildToken(String sub, long exp) {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes());
        return header + "." + payload + ".dummy-signature";
    }
}
