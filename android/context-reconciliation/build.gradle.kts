plugins { kotlin("jvm") version "2.1.21" }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }

// Standalone until the parent integrates the shared Android settings file.
tasks.register<JavaExec>("syntheticDemo") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("kr.callbrief.context.SyntheticDemoKt")
}
