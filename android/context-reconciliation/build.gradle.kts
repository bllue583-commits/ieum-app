plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }

// Also supports the standalone settings file for independent candidate-engine tests.
tasks.register<JavaExec>("syntheticDemo") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("kr.callbrief.context.SyntheticDemoKt")
}
