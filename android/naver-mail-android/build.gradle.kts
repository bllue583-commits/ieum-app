plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "kr.callbrief.navermail.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "REAL_ACCOUNT_ACTIONS_APPROVED", "false")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
    lint { abortOnError = true }
}
dependencies {
    api(project(":naver-mail-core"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    constraints {
        implementation("androidx.annotation:annotation-experimental:1.4.1")
        implementation("androidx.tracing:tracing:1.2.0")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", rootProject.layout.projectDirectory.dir("app/build/synthetic-test-home/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7").asFile.absolutePath)
}
