plugins {
    id("java-common-conventions")
    alias(libs.plugins.quarkus)
}

// 业务库模块禁用模块级主构建增强（quarkusAppPartsBuild）：本模块注入的 ChatDocsSourceResolver /
// KnowledgeBaseChatResolver / KnowledgeBaseNameResolver / ChatImageAssetUrlRewriter 端口实现在
// knowledge 模块，只有 hisilicon-knowledge-core-service 主模块聚合后 CDI 依赖才完整，库模块单独增强的
// Arc 构建期校验必然 UnsatisfiedDependency。最终增强统一由 hisilicon-knowledge-core-service 执行；
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
    // 模型服务 REST Client（HaiSI RAG Server 对接）
    implementation(libs.quarkus.rest.client.jackson)
    // 问答洞察定时分析 Job（@Scheduled cron 每天02:00）
    implementation(libs.quarkus.scheduler)
    // 敏感词缓存（sensitive 域 SensitiveWordCacheService：Redis cache-aside + 跨副本变更通知）
    implementation(libs.quarkus.redis.client)
    // 敏感词词库 CSV 导入导出（commons-csv，纯 Java native 安全）
    implementation(libs.commons.csv)
    // Quarkiverse POI 扩展：命中率仪表盘未命中列表导出 .xlsx（native image 反射/资源配置）
    implementation(libs.quarkus.poi)
    // quarkus-poi 依赖的 quarkus-awt 独立于 platform BOM，须显式固定到 core 同版本线
    implementation(libs.quarkus.awt)
    configurations.configureEach {
        resolutionStrategy.force(libs.quarkus.awt, libs.quarkus.awt.deployment)
    }

    implementation(libs.mapstruct)

    implementation(project(":commons:commons-web"))
    implementation(project(":commons:commons-errorcode"))
    implementation(project(":commons:commons-i18n"))
    implementation(project(":commons:commons-dict"))
    implementation(project(":commons:commons-sensitive"))
    // 模型服务访问适配层（ModelServiceApi/ModelServiceRestClient/KgServiceApi 等，原在本模块）
    implementation(project(":commons:commons-ai"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    // 集成测试（%it profile）连真实 PostgreSQL 需要 reactive 驱动；mock 契约测试（hibernate-orm 关闭）不受影响
    testImplementation(libs.quarkus.reactive.pg.client)

    annotationProcessor(libs.mapstruct.processor)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
