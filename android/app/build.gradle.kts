plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "kr.callbrief"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    providers.gradleProperty("eumExistingDebugKeystore").orNull?.let { existing ->
        signingConfigs.getByName("debug").storeFile = file(existing)
    }
    // CI: a stable key derived from a repository secret (tools/derive_signing_key.py), so updates install over the old app.
    System.getenv("IEUM_SIGNING_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { ks ->
        signingConfigs.getByName("debug").apply { storeFile=file(ks);storeType="pkcs12";storePassword="ieum-signing";keyAlias="ieum";keyPassword="ieum-signing" }
    }
    defaultConfig {
        applicationId = "kr.callbrief"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("IEUM_VERSION_CODE") ?: "34").toInt()
        versionName = "0.13.4-android-local-whisper-dev"
        // Subscription mode: fine-grained token for the private inbox repo only (CI secret IEUM_INBOX_TOKEN). Empty = API key mode.
        val inboxToken=(System.getenv("IEUM_INBOX_TOKEN") ?: "").trim()
        require(inboxToken.matches(Regex("[A-Za-z0-9_]*"))) { "IEUM_INBOX_TOKEN format" }
        buildConfigField("String","IEUM_INBOX_TOKEN","\""+inboxToken+"\"")
        buildConfigField("String","IEUM_INBOX_REPO","\"bllue583-commits/ieum-inbox\"")
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
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ieum-whisper/assets"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/ieum-whisper/jniLibs"))
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libieum_whisper.so" } }
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
