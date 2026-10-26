pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://devrepo.kakao.com/nexus/content/groups/public/") } }
}
rootProject.name = "CallBrief"
include(":app", ":core", ":call-core-android", ":audio-vault-android")
include(":drive-ro1-android")

include(":naver-mail-core", ":naver-mail-android")

include(":kakao-report-core", ":kakao-report-android")

include(":recording-discovery-android")
include(":context-reconciliation")
