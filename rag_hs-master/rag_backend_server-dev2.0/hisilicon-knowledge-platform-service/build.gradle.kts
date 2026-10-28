import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.net.URI
import java.util.concurrent.TimeUnit

plugins {
    id("java-common-conventions")
    alias(libs.plugins.quarkus)
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.container.image.podman)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.opentelemetry)

    implementation(project(":knowledge"))
    implementation(project(":llm"))
    implementation(project(":system"))
}

tasks.named("quarkusDev") {
    // ⚠️ 勿再写成“配置缓存序列化失败导致 quarkusDev 启动 NPE”——已证伪：
    //   Gradle 报告 cacheAction=storing（无缓存可命中 → 整图重算 → task 全新执行），
    //   2 条 Configuration/SourceSet 序列化问题在 task 执行之后才报出且条目被丢弃（discarded），属无害噪声，
    //   不进入分叉 JVM、不导致 Quarkus 运行时 firstStart 失败。
    // 真正的启动失败是 Quarkus dev 恢复路径 NPE（curatedApplication 为 null），它掩盖了首次失败原因，
    // 排障与防回归见 docs/quarkusDev-启动排障与防回归.md。
    //
    // notCompatibleWithConfigurationCache 保留：声明该交互式分叉 JVM task 永不进配置缓存（意图正确，
    // 未来若 QuarkusGradle 变可序列化也不应缓存它）；但它不会消除上面那 2 条告警
    // （QuarkusDev 仍引用 Configuration/SourceSet），属预期、可忽略。
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

// ====== RAR5 外部解压工具（方案 B：native 模式 RarFileExtractor 转调 7zz） ======
// ⚠️ 不能用 UBI 源 p7zip 代替——发行版因 unRAR 许可剥离了 RAR codec，解不了 RAR5。
// 7zz 采用"二进制入库"：提交在 src/main/docker/7zz，Dockerfile.native 直接 COPY，零网络依赖。
// 升级 7-Zip 时在有外网的机器重新下载覆盖该文件即可。本 Gradle 任务仅供本地手动预取参考，
// 不再参与镜像构建（此前的任务图挂线方案在 imageBuild/配置缓存/内网下载场景下屡次失效，已移除）。
val download7zz = tasks.register<Download7zzTask>("download7zz") {
    group = "build"
    description = "手动下载 7-Zip 官方静态 7zz 到 build/7zz（镜像用二进制入库方案，此任务仅手动预取参考）"
    archiveUrl.set(providers.gradleProperty("7zzUrl")
        .orElse("https://www.7-zip.org/a/7z2501-linux-x64.tar.xz"))
    targetFile.set(layout.buildDirectory.file("7zz"))
}
