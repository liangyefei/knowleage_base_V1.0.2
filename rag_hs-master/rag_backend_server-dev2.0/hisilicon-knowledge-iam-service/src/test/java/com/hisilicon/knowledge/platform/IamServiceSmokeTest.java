package com.hisilicon.knowledge.platform;

import com.hisilicon.knowledge.platform.system.command.UserCommandService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hisilicon-knowledge-iam-service 独立启动冒烟：证明身份域在**只聚合 system** 的 classpath 上能独立启动，
 * 且不含任何业务能力路由（反向隔离）。
 *
 * <p>identity 服务被单独拆出的前提是"它不依赖其余业务域"——本测试就是这条前提的回归防线：
 * 一旦谁把 knowledge / llm 拉进本服务，下面的 404 断言会立刻失败。</p>
 *
 * <p>⚠️ 路径不要带 {@code /hisilicon-knowledge-iam} 前缀：Quarkus 测试框架会把
 * {@code quarkus.http.root-path} 设置成 RestAssured 的 basePath，写全前缀会变成双层前缀而全 404。</p>
 */
@QuarkusTest
class IamServiceSmokeTest {

    /** 登录命令服务：注入成功即证明 system 模块的 Bean 在本服务被发现 */
    @Inject
    UserCommandService userCommandService;

    /** 对外前缀：与 core 必须不同，见 rootPathIsDistinctFromCoreService */
    @ConfigProperty(name = "quarkus.http.root-path")
    String rootPath;

    @Test
    void userCommandServiceIsInjectable() {
        assertNotNull(userCommandService, "system 身份域的登录命令服务应在本服务可注入");
    }

    @Test
    void publicKeyEndpointIsServedHere() {
        String body = given().get("/publicKey")
                .then().statusCode(200).extract().asString();
        assertTrue(body.contains("PUBLIC KEY"),
                "公钥接口应下发 PEM 公钥（证明 PemRsaUtils 与本服务的密钥资源就绪），实际: " + body);
    }

    @Test
    void loginRouteIsRegisteredHere() {
        // 只断言"路由已注册"（非 404），不锁定具体拒绝码：
        // 空体/错误媒体类型可能得到 400 或 415，语义校验由 system 模块自己的测试覆盖。
        int status = given().post("/user/login").statusCode();
        assertNotEquals(404, status, "登录路由应注册在 hisilicon-knowledge-iam-service，实际: " + status);
        assertTrue(status < 500, "请求体/媒体类型不对时应在进入业务前被拦下，不应 5xx，实际: " + status);
    }

    @Test
    void coreRoutesAreAbsentHere() {
        assertEquals(404, given().get("/system/dict/types/search").statusCode(),
                "字典路由属于 hisilicon-knowledge-core-service，不应出现在 iam");
        assertEquals(404, given().get("/system/sensitive/words/search").statusCode(),
                "敏感词路由属于 hisilicon-knowledge-core-service，不应出现在 iam");
    }

    /**
     * 对外前缀必须与 hisilicon-knowledge-core-service 区分开。
     *
     * <p>测试/生产环境经华为云 APIG 接入，APIG 按<strong>前缀</strong>转发到 CCE 内的 Service；
     * 两个服务共用 {@code /hisilicon-knowledge} 时，APIG 上无法只靠前缀区分它们
     * （只能退化成对 {@code /user/**}、{@code /publicKey} 的细粒度规则，每加一个端点都要改网关）。
     * 这条断言把"前缀必须不同"钉成回归防线——谁把它改回共用前缀，这里立刻失败。</p>
     */
    @Test
    void rootPathIsDistinctFromCoreService() {
        assertEquals("/hisilicon-knowledge-iam", rootPath,
                "iam 的对外前缀应为本服务专用前缀");
        assertNotEquals("/hisilicon-knowledge", rootPath,
                "iam 不能与 hisilicon-knowledge-core-service 共用 /hisilicon-knowledge，否则 APIG 前缀转发分不开两个服务");
    }

    /**
     * 探针端口与业务端口分离：开启 {@code quarkus.management} 后，{@code /q/health} 等非应用端点
     * 从业务端口剥离到独立的管理端口（生产 9000，测试期 9008）。
     * 注意这也让探针地址**与 root-path 无关**：两个服务的探针都是 {@code :9000/q/health}。
     */
    @Test
    void healthIsNotServedOnBusinessPort() {
        assertEquals(404, given().get("/q/health").statusCode(),
                "开启 quarkus.management 后 /q/health 只在管理端口（9008/9000）提供");
    }
}
