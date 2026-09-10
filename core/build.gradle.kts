plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is the analysis engine: domain model, target specs, scoring, statistics,
// ballistics and coaching. It is plain Kotlin/JVM on purpose - no Android, no OpenCV -
// so all of it is unit-testable on any machine.
// Target Java 17 bytecode (what the Android build consumes) without demanding a JDK 17
// toolchain, so this builds on any JDK 17+.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
