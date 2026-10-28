plugins {
    id("java-common-conventions")
}

// 模型服务访问适配层（从 llm 抽出，解 llm <-> knowledge 的编译期环）。
//
// 本模块是纯库模块：
// - 不应用 io.quarkus 插件（无 @QuarkusTest 需求，避免空 Quarkus 应用增强失败）；
// - 靠 src/main/resources/META-INF/beans.xml 声明为 bean archive，并被 Quarkus 索引，
//   使 @ApplicationScoped 实现与 @RegisterRestClient 接口在依赖它的模块中可被发现
//   （与 commons-dict 同一机制）；
// - 依赖方（llm / knowledge）由 Quarkus 运行时聚合，最终增强统一在主服务模块执行。
dependencies {
    // CDI（@ApplicationScoped）
    implementation(libs.quarkus.arc)
    // REST Client（@RegisterRestClient / @RestClient / @RegisterProvider）+ Jackson 编解码
    implementation(libs.quarkus.rest.client.jackson)
    // Jackson（DTO 上 @JsonProperty/@JsonIgnoreProperties/@JsonInclude、SSE 帧 JsonNode 解析）
    implementation(libs.quarkus.rest.jackson)

    // 三个端口与其响应类型（DocCodeMappingClient / DocumentChunkClient / KnowledgeGraphClient）
    implementation(project(":commons:commons-dict"))
    // 统一错误码（BusinessException / ErrorCodeEnum）
    implementation(project(":commons:commons-errorcode"))
    // 国际化（AppMessages / LocaleUtil）
    implementation(project(":commons:commons-i18n"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
