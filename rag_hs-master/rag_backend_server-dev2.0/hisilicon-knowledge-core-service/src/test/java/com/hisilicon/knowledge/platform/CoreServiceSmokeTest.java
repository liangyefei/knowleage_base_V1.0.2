package com.hisilicon.knowledge.platform;

import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordChecker;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * hisilicon-knowledge-core-service 独立启动冒烟：证明本服务在**不含 system（身份域）**的 classpath 上
 * 能完成 Quarkus 增强与启动，且路由归属符合拆分设计。
 *
 * <p>为什么值得单独写（各业务模块已有自己的 @QuarkusTest）：模块测试是在模块自身的
 * classpath 上跑的，验证不了"换成服务级 classpath 后 CDI 是否还完整"。
 * 典型漏网场景是某个 Bean 依赖了只存在于 system 的实现——模块测试各自补齐桩，
 * 服务级启动才会暴露 UnsatisfiedDependency。</p>
 *
 * <p>断言策略：不连真实 DB（hibernate 关闭），因此不校验业务语义，只校验
 * ①核心 Bean 可注入；②属于本服务的路由已注册（非 404）；③属于 iam 的路由不存在（404）。</p>
 *
 * <p>⚠️ 路径不要带 {@code /hisilicon-knowledge} 前缀：Quarkus 测试框架会把
 * {@code quarkus.http.root-path} 设置成 RestAssured 的 basePath，写全前缀会变成双层前缀而全 404
 * （与 knowledge/llm 模块测试一致，它们同样只写 {@code /kg/projects} 这样的相对路径）。</p>
 */
@QuarkusTest
class CoreServiceSmokeTest {

    /** 敏感词端口：实现随敏感词域落在 llm 模块，注入成功即证明 llm 的 Bean 在本服务被发现 */
    @Inject
    SensitiveWordChecker sensitiveWordChecker;

    /** 对外前缀：必须与 hisilicon-knowledge-iam-service 不同，见 rootPathIsDistinctFromIamService */
    @ConfigProperty(name = "quarkus.http.root-path")
    String rootPath;

    @Test
    void sensitiveWordPortIsInjectable() {
        assertNotNull(sensitiveWordChecker, "llm 敏感词域的敏感词端口应在本服务可注入");
    }

    @Test
    void dictRoutesAreRegisteredHere() {
        // 无令牌 → commons-web 的 @Authenticated 过滤器抛 A0301，由统一异常处理转成业务响应；
        // 只要不是 404 就说明该资源确实注册在本服务（字典域已归 knowledge 模块）
        int status = given().get("/system/dict/types/search").statusCode();
        assertNotEquals(404, status, "字典路由应注册在 hisilicon-knowledge-core-service");
    }

    @Test
    void sensitiveWordRoutesAreRegisteredHere() {
        int status = given().get("/system/sensitive/words/search").statusCode();
        assertNotEquals(404, status, "敏感词路由应注册在 hisilicon-knowledge-core-service");
    }

    @Test
    void iamRoutesAreAbsentHere() {
        assertEquals(404, given().get("/publicKey").statusCode(),
                "公钥接口属于 hisilicon-knowledge-iam-service，不应出现在 core");
        assertEquals(404, given().post("/user/login").statusCode(),
                "登录接口属于 hisilicon-knowledge-iam-service，不应出现在 core");
    }

    /**
     * 本服务保留拆分前的对外前缀（旧前端与旧服务一致，切换时只换后端上游）；
     * 同时断言它**不等于** hisilicon-knowledge-iam-service 的前缀——APIG 按前缀转发，两个服务同前缀分不开。
     */
    @Test
    void rootPathIsDistinctFromIamService() {
        assertEquals("/hisilicon-knowledge", rootPath,
                "core 应保留拆分前的对外前缀，旧前端路径一字不改");
        assertNotEquals("/hisilicon-knowledge-iam", rootPath,
                "core 不能与 hisilicon-knowledge-iam-service 共用前缀，否则 APIG 前缀转发分不开两个服务");
    }

    /**
     * 探针端口与业务端口分离：开启 {@code quarkus.management} 后，{@code /q/health} 等非应用端点
     * 从业务端口剥离到独立的管理端口（生产 9000，测试期 9007）。
     * 这条断言守住"探针不出现在业务端口上"——业务端口是要经 APIG 暴露给前端的。
     */
    @Test
    void healthIsNotServedOnBusinessPort() {
        assertEquals(404, given().get("/q/health").statusCode(),
                "开启 quarkus.management 后 /q/health 只在管理端口（9007/9000）提供");
    }
}
