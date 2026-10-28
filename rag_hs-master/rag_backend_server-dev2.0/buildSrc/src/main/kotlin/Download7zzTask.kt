import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * RAR5 外部解压工具（方案 B）下载任务：native 模式下 RarFileExtractor 转调 7zz 外部进程，
 * Dockerfile.native COPY build/7zz。实现为独立任务类（buildSrc），而非 build.gradle.kts 里的
 * doLast 闭包——闭包捕获脚本对象引用会被 Gradle 配置缓存拒绝。
 * ⚠️ 不能用 UBI 源 p7zip 代替——发行版因 unRAR 许可剥离了 RAR codec，解不了 RAR5。
 */
abstract class Download7zzTask : DefaultTask() {

    /** 下载地址；默认 7-zip.org 官方 linux-x64 包，可用 -P7zzUrl=... 覆盖（内网镜像等） */
    @get:Input
    abstract val archiveUrl: Property<String>

    @get:OutputFile
    abstract val targetFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = targetFile.get().asFile
        if (target.exists()) {
            logger.lifecycle("7zz 已存在，跳过下载: {}", target.absolutePath)
            return
        }
        val url = archiveUrl.get()
        target.parentFile.mkdirs()
        val archive = Files.createTempFile("7zz", ".tar.xz").toFile()
        try {
            logger.lifecycle("下载 RAR5 外部解压工具: {}", url)
            java.net.URI.create(url).toURL().openStream().use { input ->
                Files.copy(input, archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // tar.xz 解包：交给系统 tar（linux CI 必有；Windows 10+ 自带 bsdtar），避免依赖 Gradle 对 xz 的支持
            val extractDir = Files.createTempDirectory("7zz-extract").toFile()
            val proc = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", extractDir.absolutePath, "7zz")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.readAllBytes().decodeToString()
            if (!proc.waitFor(120, TimeUnit.SECONDS) || proc.exitValue() != 0) {
                proc.destroyForcibly()
                throw GradleException(
                    "解压 7zz 失败（exit=${proc.exitValue()}）: $out\n" +
                        "可手动下载 $url 并将 7zz 放到 ${target.absolutePath}")
            }
            Files.copy(Path.of(extractDir.absolutePath, "7zz"), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
            // docker COPY --chmod=0755 已保证容器内可执行；此处 setExecutable 兜底本地直接运行场景
            target.setExecutable(true)
            logger.lifecycle("7zz 就绪: {}", target.absolutePath)
        } finally {
            archive.delete()
        }
    }
}
