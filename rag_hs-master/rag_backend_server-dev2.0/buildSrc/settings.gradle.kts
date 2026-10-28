pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    // 仓库配置
    repositories {
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
    }
    // 版本目录配置
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}