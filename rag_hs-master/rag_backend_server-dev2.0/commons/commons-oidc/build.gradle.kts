plugins {
    id("java-common-conventions")
}

// OIDC / 华为账号（OneAccess）通用组件：资源服务器侧平台 JWT 验签 + RP 侧外部账号校验。
//
// 本模块是纯库模块（与 commons-ai / commons-web 同一形态）：
// - 不应用 io.quarkus 插件（无 @QuarkusTest 需求），靠 src/main/resources/META-INF/beans.xml
//   声明为 bean archive，并被依赖方服务索引发现 @ApplicationScoped Bean；
// - 平台 BOM 仅用于依赖版本对齐（io.quarkus 插件缺席时无法全局 platform()）。
dependencies {
    // Quarkus BOM：为下方不带版本的 quarkus-smallrye-jwt / jose4j 提供版本管理
    implementation(platform(libs.quarkus.bom))
    // CDI（@ApplicationScoped / 构造函数注入）
    implementation(libs.quarkus.arc)
    // 平台 JWT 本地验签（smallrye-jwt，其验签引擎 jose4j 经传递依赖可用，测试侧同库生成签名 token）
    implementation(libs.quarkus.smallrye.jwt)
    // REST Client（RestClientBuilder / @RegisterRestClient / WebApplicationException）+ Jackson 编解码
    implementation(libs.quarkus.rest.client.jackson)

    // 统一错误码（BusinessException / ErrorCodeEnum）
    implementation(project(":commons:commons-errorcode"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
