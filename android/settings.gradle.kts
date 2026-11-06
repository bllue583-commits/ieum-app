pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // v3.5 (DESIGN_V5): Kakao Android SDK v2 from Kakao's own repository first, for its group only (Maven Central
        // also has it, but the cloud session proxy answers 429 there).
        maven("https://devrepo.kakao.com/nexus/content/groups/public/") { content { includeGroup("com.kakao.sdk") } }
        google(); mavenCentral()
    }
}
rootProject.name = "CallBrief"
include(":app", ":core", ":call-core-android", ":audio-vault-android")
include(":recording-discovery-android")
