plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "kr.callbrief.kakao.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        val nativeKey = providers.gradleProperty("kakao.nativeAppKey").orElse("").get()
        manifestPlaceholders["kakaoNativeAppKey"] = nativeKey.ifBlank { "not-configured" }
        resValue("string","kakao_native_app_key",nativeKey)
        resValue("integer","kakao_developer_app_id",providers.gradleProperty("kakao.developerAppId").orElse("0").get().toInt().toString())
        resValue("string","kakao_public_landing_url",providers.gradleProperty("kakao.publicLandingUrl").orElse("").get())
        val connect = providers.gradleProperty("kakao.connectionEnabled").orElse("false").get()
        val send = providers.gradleProperty("kakao.liveSendEnabled").orElse("false").get()
        require(connect in listOf("true","false") && send in listOf("true","false"))
        resValue("bool","kakao_connection_enabled",connect)
        resValue("bool","kakao_live_send_enabled",send)
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}
dependencies {
    api(project(":kakao-report-core"))
    implementation("com.kakao.sdk:v2-user:2.25.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
tasks.withType<Test>().configureEach {
    systemProperty("user.home", layout.buildDirectory.dir("synthetic-test-home").get().asFile.absolutePath)
    providers.gradleProperty("kakao.robolectricDependencyDir").orElse(rootProject.layout.projectDirectory.dir("app/build/synthetic-test-home/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7").asFile.absolutePath).orNull?.let {
        systemProperty("robolectric.offline","true")
        systemProperty("robolectric.dependency.dir",it)
    }
}
