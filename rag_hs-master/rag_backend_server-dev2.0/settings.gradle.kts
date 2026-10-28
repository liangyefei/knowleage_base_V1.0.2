pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name="rag_backend_server"
include(
    "system",
    "knowledge",
    "llm",
    "hisilicon-knowledge-platform-service",
    // 拆分后的两个部署单元（旧的 hisilicon-knowledge-platform-service 保留至拆分收尾，之后下线）
    "hisilicon-knowledge-core-service",
    "hisilicon-knowledge-iam-service",
    // 预留骨架：只占位、不参与任何运行时 classpath，后续在其中加实现
    "hisilicon-knowledge-mcp-gateway",
    "commons:commons-i18n",
    "commons:commons-errorcode",
    "commons:commons-web",
    "commons:commons-abac",
    "commons:commons-dict",
    "commons:commons-sensitive",
    "commons:commons-ai",
    "commons:commons-oidc"
)
