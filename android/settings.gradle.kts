// 镜像开关：默认走阿里云镜像（国内快）；海外 / CI 用 -PuseMirror=false 直连官方源。
val useMirror = (providers.gradleProperty("useMirror").orNull ?: "true") != "false"

pluginManagement {
    val mirror = (settings.providers.gradleProperty("useMirror").orNull ?: "true") != "false"
    repositories {
        if (mirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "AiPhonePeek"
include(":app")
