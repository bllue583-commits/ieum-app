plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
tasks.register<JavaExec>("contractTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("kr.callbrief.kakao.core.ContractTests")
    jvmArgs("--add-modules=jdk.httpserver")
}

tasks.register<JavaExec>("productionConnectionTest") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("kr.callbrief.kakao.core.ProductionConnectionTests")
}
