pluginManagement {
    repositories {
        // 国内镜像优先（解决下载慢/失败问题）
        maven { url = uri("https://maven.aliyun.com/repository/google/") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin/") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter/") }
        // 华为云镜像备用
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        // 官方源兜底
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 核心优化：只保留阿里云镜像（足够稳定，避免多镜像冲突）
        maven { url = uri("https://maven.aliyun.com/repository/google/") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        // 官方源兜底
        google()
        mavenCentral()
    }
}

rootProject.name = "AIDiaryApp"
include(":app")
