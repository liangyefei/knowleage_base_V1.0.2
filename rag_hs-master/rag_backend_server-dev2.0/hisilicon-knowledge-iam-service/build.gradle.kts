plugins {
    id("java-common-conventions")
    alias(libs.plugins.quarkus)
}

// hisilicon-knowledge-iam-service：身份与访问服务（用户/角色/权限/登录/令牌 + RSA 密钥对 + 本地口令库）。
//
// 只聚合 system 模块；**不引入 knowledge / llm**——身份域对其余业务零编译期依赖，
// 反向也成立：core 不需要调用 iam（commons-web 的 AuthenticationFilter 只解析令牌载荷，
// 不校验签名、不查库，见评估文档 P0 说明）。因此两个服务的可用性互不阻塞。
//
// 业务端口 8080、探针端口 9000（与 core 同值，CCE 里各自独立容器不冲突）；
// root-path 与 core **必须不同**（/hisilicon-knowledge-iam）——测试/生产经华为云 APIG 接入，
// APIG 按前缀转发到 CCE 内的 Service，同前缀下分不开两个服务：
//   /hisilicon-knowledge-iam/**  -> 本服务（/user/login、/refresh、/logout、/publicKey）
//   /hisilicon-knowledge/**      -> hisilicon-knowledge-core-service
//   浏览器可见的 /hisilicon-knowledge/user/** 与 /publicKey 由前端 nginx/APIG rewrite 到本前缀（前端零改动）
dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.container.image.podman)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.opentelemetry)

    implementation(project(":system"))

    // OneAccess OIDC 授权码流程（web-app）：回调 code 换 token / ID Token 验签 / 会话建立
    // 全部由 quarkus-oidc 扩展承担；system 模块只消费 SecurityIdentity 与 OidcSession API。
    implementation(libs.quarkus.oidc)

    // 服务级独立启动冒烟（IamServiceSmokeTest）：反向隔离防线——若谁把 knowledge/llm 拉进本服务，
    // 测试里的 404 断言会立刻失败
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named("quarkusDev") {
    // 同 hisilicon-knowledge-core-service：该交互式分叉 JVM task 永不进配置缓存；
    // 配置缓存相关告警属无害噪声，勿据此改判为启动失败（排障见 docs/quarkusDev-启动排障与防回归.md）。
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
