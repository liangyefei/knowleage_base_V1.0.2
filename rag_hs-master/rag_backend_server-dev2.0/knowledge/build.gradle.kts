plugins {
    id("java-common-conventions")
    alias(libs.plugins.quarkus)
}

// 业务库模块禁用模块级主构建增强（quarkusAppPartsBuild）：本模块注入的 KnowledgeGraphClient /
// DocCodeMappingClient / DocumentChunkClient 端口实现在 commons-ai 模块（原在 llm），只有
// service 主模块聚合后 CDI 依赖才完整，库模块单独增强的 Arc 构建期校验必然 UnsatisfiedDependency。
// 最终增强统一由 hisilicon-knowledge-platform-service 执行；io.quarkus 插件本身必须保留，
// @QuarkusTest 依赖插件注册的 ApplicationModel tooling model 启动测试容器。
tasks.matching { it.name == "quarkusAppPartsBuild" }.configureEach {
    enabled = false
}

dependencies {
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.hibernate.reactive)
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.mapstruct)
    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.redis.client)
    // 响应式 PG 客户端：document_contents 抽取落库走连接池直连（不经 Hibernate session，
    // 避免 fire-and-forget 索引与外层事务同 session 交错；运行时由 service 模块装配该扩展）
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.messaging.kafka)
    // Quarkiverse POI 扩展：为 POI 提供 native image 所需反射/资源配置（docx/xlsx/pptx 抽取依赖 POI）
    implementation(libs.quarkus.poi)
    // quarkus-poi 依赖的 quarkus-awt 独立于 platform BOM，须显式固定到 core 同版本线
    implementation(libs.quarkus.awt)
    configurations.configureEach {
        resolutionStrategy.force(libs.quarkus.awt, libs.quarkus.awt.deployment)
    }

    implementation(libs.mapstruct)
    implementation(libs.commons.compress)
    implementation(libs.zstd.jni)
    // RAR 只读解压：RAR4 走 junrar（纯 Java）；RAR5 主线 junrar 不支持，走 7-Zip-JBinding（JNI，
    // all-platforms 运行期解出 native 库）。⚠️ native image 不可用（JNI）；用法隔离在 RarFileExtractor
    implementation(libs.junrar)
    implementation(libs.sevenzipjbinding)
    implementation(libs.sevenzipjbinding.all.platforms)
    // 文档文本抽取（全局搜索按内容检索）：pdf/word/excel/ppt + txt/html/md（用法隔离在 DocumentContentIndexService）
    implementation(libs.tika.parsers.standard)
    implementation(libs.tika.core)
    // 版本对比（version-diff）：行级 diff 算法
    implementation(libs.java.diff.utils)

    // 全局排除 Jakarta Mail 实现（angus-activation / jakarta.mail）：
    // tika-parser-mail 经 jaxb-core 传递引入 angus-activation，其 GraalVM Feature（AngusActivationFeature）
    // 在 native 分析期反射 jakarta.mail.Part（optional 依赖，不在 classpath）→ native 构建崩溃（NoClassDefFoundError）。
    // 本项目不解析邮件类文档，Quarkus/Hibernate 均不依赖 Jakarta Mail 实现，模块级排除无功能影响。
    configurations.configureEach {
        exclude(group = "org.eclipse.angus")
        exclude(group = "com.sun.mail")
        exclude(group = "jakarta.mail")
    }

    implementation(project(":commons:commons-web"))
    implementation(project(":commons:commons-errorcode"))
    implementation(project(":commons:commons-i18n"))
    implementation(project(":commons:commons-dict"))
    // 模型服务访问适配层：提供 DocCodeMappingClient / DocumentChunkClient / KnowledgeGraphClient
    // 三个端口的真实实现（原由 llm 模块提供，解环后归 commons-ai）
    implementation(project(":commons:commons-ai"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    // 纯 JUnit+Mockito 单测（DocCodeMappingQueryServiceTest：版本解析/模型错误转换，不启容器）
    testImplementation(libs.quarkus.junit5.mockito)
    // 集成测试（%it profile）连真实 PostgreSQL 需要 reactive 驱动；mock 契约测试（hibernate-orm 关闭）不受影响
    testImplementation(libs.quarkus.reactive.pg.client)

    annotationProcessor(libs.mapstruct.processor)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // 测试 JVM 临时目录重定向到 build 目录：受限环境（如沙箱）下系统 java.io.tmpdir
    // 可能不可写，Quarkus WebJarProcessor 启动时在其下 createTempDirectory 会直接
    // AccessDeniedException，导致所有 @QuarkusTest 类无法 bootstrap
    val testTmp = layout.buildDirectory.dir("test-tmp").get().asFile
    testTmp.mkdirs()
    jvmArgs("-Djava.io.tmpdir=${testTmp.absolutePath}")
    // 集成测试（*IT）在独立 source set src/integrationTest，不影响 mock 契约测试
}

// ====== 集成测试 source set（真实 PostgreSQL + Hibernate Reactive）======
// 该 source set 由 io.quarkus 插件隐式注册（Vanilla Gradle 不会自动注册），这里只补充编译/运行 classpath
sourceSets {
    integrationTest {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

/**
 * 集成测试任务：跑真实 PostgreSQL（Podman postgres:18.4 / kb_test 库）+ Hibernate Reactive。
 * <p>datasource / hibernate-orm.enabled 是 build-time 配置，QuarkusTestProfile.getConfigOverrides()
 * 在 augmentation 之后才应用（无效），必须经系统属性注入（build 阶段可见）。</p>
 */
tasks.register<Test>("integrationTest") {
    useJUnitPlatform()
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath

    systemProperty("quarkus.datasource.db-kind", "postgresql")
    systemProperty("quarkus.datasource.reactive.url", "vertx-reactive:postgresql://localhost:5432/kb_test")
    systemProperty("quarkus.datasource.username", "postgres")
    systemProperty("quarkus.datasource.password", "123456")
    systemProperty("quarkus.hibernate-orm.enabled", "true")
    systemProperty("quarkus.hibernate-orm.database.generation", "drop-and-create")
    // @JdbcTypeCode(SqlTypes.JSON) 列（import_batches.default_metadata / upload_sessions.metadata）
    // 用 Hibernate 内建 JSON mapper，不复用被 REST 定制的全局 ObjectMapper，否则 Quarkus 因检测到
    // LocalDateTimeJacksonCustomizer 等 bean 而拒绝构建 EMF（BuiltinFormatMapperBehaviour:48）
    systemProperty("quarkus.hibernate-orm.mapping.format.global", "ignore")
    // 避免与 mock 契约测试（8081）端口冲突
    systemProperty("quarkus.http.test-port", "8083")
    // 文件存储根目录：IT 写真实 zip 临时文件 + 解压落库后的文档文件（不依赖 /data/kb-files，用 build 临时目录）
    systemProperty("kb.storage.local-path", layout.buildDirectory.dir("it-storage").get().asFile.absolutePath)
}
