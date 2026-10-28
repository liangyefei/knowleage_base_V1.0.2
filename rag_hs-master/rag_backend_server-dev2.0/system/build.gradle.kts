plugins {
    id("java-common-conventions")
    alias(libs.plugins.quarkus)
}

// 身份与访问域模块（用户/角色/权限/登录/令牌）。
//
// 本模块已不含任何跨模块 CDI 端口依赖：字典域（原 system 内）已归 knowledge，敏感词域已归 llm。
// quarkusAppPartsBuild 仍然禁用——本模块不承载主应用，最终增强统一由 hisilicon-knowledge-iam-service 执行；
// io.quarkus 插件本身必须保留，@QuarkusTest 依赖插件注册的 ApplicationModel tooling model
// 启动测试容器。
tasks.matching { it.name == "quarkusAppPartsBuild" }.configureEach {
    enabled = false
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.hibernate.reactive)
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.mapstruct)
    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.rest.jackson)

    implementation(libs.mapstruct)

    implementation(project(":commons:commons-web"))
    implementation(project(":commons:commons-errorcode"))
    implementation(project(":commons:commons-i18n"))

    // 登录/认证相关
    implementation(libs.quarkus.elytron.security.common)
    implementation(libs.quarkus.smallrye.jwt)
    implementation(libs.quarkus.redis.client)
    // OIDC（OneAccess 授权码流程）：system 需要编译到 io.quarkus.security.Authenticated /
    // io.quarkus.oidc.OidcSession。扩展的实际装配仍由部署单元 hisilicon-knowledge-iam-service 决定
    // （见该模块 build.gradle.kts），本模块只是把 SDK/扩展 API 放进编译期 classpath。
    implementation(libs.quarkus.oidc)
    // 外部账号校验端口（commons-oidc）：刷新令牌时 fail-closed 校验 OneAccess access token
    implementation(project(":commons:commons-oidc"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.junit5.mockito)
    testImplementation(libs.rest.assured)

    annotationProcessor(libs.mapstruct.processor)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// 私钥禁止进入 native 镜像（生产由 K8s Secret 注入 rsa.private-key，本地 JVM dev 走 classpath 兜底）。
// 不用 quarkus.native.resources.excludes——Quarkus 3.29 起弃用且无替代（reachability-metadata.json
// 不支持资源排除）。改为 native 构建时（-Dquarkus.native.enabled=true）直接不打包该资源；
// dev/test（未传该属性）时 privateKey.pem 照常进 classpath。
tasks.named<ProcessResources>("processResources") {
    if (System.getProperty("quarkus.native.enabled") == "true") {
        exclude("privateKey.pem")
    }
}