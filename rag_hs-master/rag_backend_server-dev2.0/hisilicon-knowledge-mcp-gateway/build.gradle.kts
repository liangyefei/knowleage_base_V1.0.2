plugins {
    id("java-common-conventions")
    alias(libs.plugins.quarkus)
}

// hisilicon-knowledge-mcp-gateway：面向 agent 的**入站** MCP 聚合网关。
//
// 对外暴露**一个** MCP Streamable HTTP 端点，对内聚合模型侧**多个** MCP 工具服务
// （hisi-code-mcp :9800 / hisi-codegraph :9801 / hisi-docs-mcp :9802 / hisi-docs-search :9803，
// 上游清单配置化，增减不发版）。不做 agent 循环、不做 LLM 编排——tool-calling 循环在外部 agent 侧。
//
// ⚠️ 与"平台调用模型的 MCP"方向相反（最易混的一点）：
//   本模块：**入站** agent → 平台；commons-ai：**出站** 平台 → dual-mcp（REST，:9789）。
//   模型侧若也要走 MCP 协议，客户端放 commons-ai，不要放进本模块。
//
// 协议层用官方 Quarkiverse 扩展 quarkus-mcp-server-http（版本锁定理由见 gradle/libs.versions.toml）。
// 用它的**编程式 ToolManager API**（newTool/setInputSchema/setHandler）而非 @Tool 注解：
// 上游工具清单是**运行期拉取**的（且上游会升级版本、工具会增减），注解式要求编译期静态方法，不适用。
// 即：JSON-RPC 编解码 / initialize 握手 / 传输 / 会话全交给扩展，本模块只写"拉清单 → 注册 → 转发"。
//
// 端口与路由：业务 8080 / 探针 9000（与 core、iam 同值——CCE 里各自独立容器，同值不冲突），
// root-path **必须**是独立前缀 /hisilicon-knowledge-mcp：测试/生产经华为云 APIG 按**前缀**转发到
// CCE 内的 Service，三个服务前缀必须两两不同，否则 APIG 分不开。
//
// 鉴权（阶段 2，本轮未做）：入站请求将携带**平台签发的 JWT**，网关侧应用 commons-oidc 的
// PlatformJwtVerifier 做本地 RS256 验签后再信任 sub/roles；**不要**复用 commons-web 的
// AuthenticationFilter（它目前只做 Base64 解析、不验签，P0 旧账）。
dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.container.image.podman)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.opentelemetry)

    // MCP 协议层（Streamable HTTP 传输 + ToolManager 编程式注册 API）
    implementation(libs.quarkus.mcp.server.http)

    // 调用上游 MCP 服务用的 Mutiny 版 Vert.x WebClient
    implementation(libs.quarkus.vertx.http)
    implementation(libs.smallrye.mutiny.vertx.web.client)

    // 运维/自检 REST 面（GatewayAdminResource）
    implementation(libs.quarkus.rest.jackson)

    // 统一错误码（BusinessException / ErrorCodeEnum，McpUpstreamException 继承它）
    implementation(project(":commons:commons-errorcode"))

    // 阶段 2 入站鉴权：平台 JWT 本地验签（PlatformJwtVerifier）+ 吊销/限额/计量的 Redis 存储。
    // 刻意不引 commons-web（会连带 Hibernate Validator/MapStruct 等，模块依赖最小化口径）。
    implementation(project(":commons:commons-oidc"))
    implementation(libs.quarkus.redis.client)
    // jose4j：PlatformJwtVerifier#verify 返回 JwtClaims，McpAuthFilter 读 sub/jti/roles 需要它的类型
    implementation(libs.jose4j)

    // 服务级独立启动冒烟（McpGatewayServiceSmokeTest）+ 聚合/路由/失败隔离测试
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    // McpAssured：官方测试客户端，按真实 MCP 协议做 tools/list、tools/call 断言
    testImplementation(libs.quarkus.mcp.server.test)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named("quarkusDev") {
    // 同 core / iam：该交互式分叉 JVM task 永不进配置缓存，
    // 配置缓存相关告警属无害噪声，勿据此改判为启动失败。
    notCompatibleWithConfigurationCache("quarkusDev forks JVM with interactive console, incompatible with configuration cache")
    setProperty("jvmArgs", listOf(
        "-Dfile.encoding=UTF-8",
        "-Dconsole.encoding=UTF-8",
        "-Dnative.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Dorg.jboss.logging.encoding=UTF-8"
    ))
}