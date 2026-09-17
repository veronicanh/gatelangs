plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// ---------------------------------------------------------------------------
// ./gradlew verifySetup
//
// Compiles both targets and runs the tests — proves every dependency and the
// JDK 21 toolchain are downloaded before you need them.
// ---------------------------------------------------------------------------
tasks.register("verifySetup") {
    group = "build"
    description = "Compiles Desktop + Web and runs the tests, pre-downloading everything."

    dependsOn(":composeApp:compileKotlinJvm", ":composeApp:jvmTest")
    dependsOn(":composeApp:wasmJsBrowserDevelopmentWebpack")

    doLast {
        println()
        println("Gatelangs setup check")
        println("---------------------")
        println("  OK  JDK for Gradle: ${System.getProperty("java.version")} (toolchain 21 auto-provisioned)")
        println("  OK  Desktop (JVM) + Web (Wasm) both compile")
        println("  OK  Dependencies + JDK 21 toolchain downloaded (this build proved it)")
        println()
        println("  ./gradlew :composeApp:run                             # Desktop")
        println("  ./gradlew :composeApp:wasmJsBrowserDevelopmentRun     # Web")
        println()
    }
}
