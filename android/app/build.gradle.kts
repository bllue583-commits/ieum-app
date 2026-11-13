plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "kr.callbrief"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    providers.gradleProperty("eumExistingDebugKeystore").orNull?.let { existing ->
        signingConfigs.getByName("debug").storeFile = file(existing)
    }
    // CI: a stable key derived from the secret IEUM_SIGNING_SEED, or from IEUM_INBOX_TOKEN while that secret is empty
    // (tools/derive_signing_key.py), so updates install over the old app. The seed itself never reaches Gradle.
    System.getenv("IEUM_SIGNING_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { ks ->
        signingConfigs.getByName("debug").apply { storeFile=file(ks);storeType="pkcs12";storePassword="ieum-signing";keyAlias="ieum";keyPassword="ieum-signing" }
    }
    defaultConfig {
        applicationId = "kr.callbrief"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("IEUM_VERSION_CODE") ?: "34").toInt()
        // The name people see (v2.0, v2.1 …) comes from docs/VERSION; the build number above only orders updates.
        versionName = rootProject.file("../docs/VERSION").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: "0.13.4-android-local-whisper-dev"
        // Subscription mode: fine-grained token for the private inbox repo only (CI secret IEUM_INBOX_TOKEN). Empty = API key mode.
        val inboxToken=(System.getenv("IEUM_INBOX_TOKEN") ?: "").trim()
        require(inboxToken.matches(Regex("[A-Za-z0-9_]*"))) { "IEUM_INBOX_TOKEN format" }
        buildConfigField("String","IEUM_INBOX_TOKEN","\""+inboxToken+"\"")
        buildConfigField("String","IEUM_INBOX_REPO","\"bllue583-commits/ieum-inbox\"")
        // v3.5 (DESIGN_V5): Kakao native app key (CI secret IEUM_KAKAO_APP_KEY, release-apk). Empty = the KakaoTalk morning
        // report hides itself in settings. Not a secret in itself, but the repository is public, so it is never in the code.
        val kakaoKey=(System.getenv("IEUM_KAKAO_APP_KEY") ?: "").trim()
        require(kakaoKey.matches(Regex("[A-Za-z0-9]*"))) { "IEUM_KAKAO_APP_KEY format" }
        buildConfigField("String","KAKAO_APP_KEY","\""+kakaoKey+"\"")
        // r18 (user 11/13): the user's own 5-item list template from the Kakao console (CI variable IEUM_KAKAO_LIST_TEMPLATE_ID,
        // empty = Kakao's default list of 3) and the web view's address (IEUM_KAKAO_WEB_BASE, the GitHub Pages docs/report/ page,
        // empty = no [웹으로 보기]). Neither is a secret; both stay out of the code like the app key.
        val listTemplate=(System.getenv("IEUM_KAKAO_LIST_TEMPLATE_ID") ?: "").trim()
        require(listTemplate.matches(Regex("[0-9]{0,18}"))) { "IEUM_KAKAO_LIST_TEMPLATE_ID format" }
        buildConfigField("long","KAKAO_LIST_TEMPLATE_ID",listTemplate.ifEmpty { "0" }+"L")
        val webBase=(System.getenv("IEUM_KAKAO_WEB_BASE") ?: "").trim()
        require(webBase.isEmpty() || webBase.matches(Regex("https://[A-Za-z0-9.-]+/[A-Za-z0-9._~/-]*"))) { "IEUM_KAKAO_WEB_BASE format" }
        buildConfigField("String","KAKAO_WEB_BASE","\""+webBase+"\"")
        // The login redirect and the message button's scheme: kakao<key>://oauth, kakao<key>://kakaolink.
        manifestPlaceholders["kakaoScheme"]=if(kakaoKey.isEmpty()) "kakaonone" else "kakao$kakaoKey"
        // r15-4 (L1): without a key the login's way back (AuthCodeHandlerActivity) is switched off in the manifest.
        manifestPlaceholders["kakaoEnabled"]=if(kakaoKey.isEmpty()) "false" else "true"
        // Local design preview (IEUM_LOCAL_PREVIEW=1, never in CI): sample calls on first launch, nothing sent anywhere.
        buildConfigField("boolean","LOCAL_PREVIEW",(System.getenv("IEUM_LOCAL_PREVIEW")=="1").toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") { buildConfigField("boolean","SYNTHETIC_TOOLS_ENABLED","true") }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            buildConfigField("boolean","SYNTHETIC_TOOLS_ENABLED","false")
            // v3.8 r18-lock (DESIGN_V7 4.2-3): the very same signing config as debug (the CI key above, or the local debug
            // key), so the release APK carries the certificate every release so far had and installs over them.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ieum-whisper/assets"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/ieum-whisper/jniLibs"))
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libieum_whisper.so" }
        // Notice files some library jars ship twice; left out so packaging never fails on a duplicate.
        resources { excludes += setOf("META-INF/NOTICE.md","META-INF/LICENSE.md") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
    lint { abortOnError = true }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core"))
    implementation(project(":recording-discovery-android"))
    implementation(project(":call-core-android"))
    implementation(project(":audio-vault-android"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // v3.5 (DESIGN_V5): KakaoTalk "나에게 보내기" (login, talk_message, image upload). Only KakaoSdkSender uses it.
    // 2.24.0, not the design's 2.26.0: Kakao's repository serves 2.24.0 as its latest release (11/6, r15-1).
    implementation("com.kakao.sdk:v2-user:2.24.0")
    implementation("com.kakao.sdk:v2-talk:2.24.0")
    implementation("com.kakao.sdk:v2-share:2.24.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
tasks.withType<Test>().configureEach {
    val testHome=layout.buildDirectory.dir("synthetic-test-home").get().asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home",testHome.absolutePath)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("synthetic-test-home/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7").get().asFile.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
